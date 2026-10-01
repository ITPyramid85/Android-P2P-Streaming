#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#define _WIN32_WINNT 0x0601
// One thread select()s over every phone socket, so the default 64-socket fd_set is
// far too small. Must be set before winsock2.h.
#define FD_SETSIZE 1024

#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>

#include <algorithm>
#include <atomic>
#include <condition_variable>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <cstddef>
#include <deque>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#pragma comment(lib, "ws2_32.lib")
#pragma comment(lib, "advapi32.lib")

// Signaling relay
// ---------------
//   phone ──TCP──► relay ◄──TCP── desktop
//                    │  pair check + SDP/ICE only
//                    ▼
//          phone ◄══ WebRTC P2P (STUN, TURN fallback) ══► desktop
//
// The relay checks that the phone is registered, then tells both sides they are
// paired and which ICE servers to use. After that it forwards only SDP offers,
// answers, and ICE candidates. Video and input never pass through this process:
// they ride a WebRTC data channel directly between the two peers.

namespace {

constexpr uint8_t FLAG_CONTROL = 0x43;
constexpr uint8_t FLAG_VIDEO = 0x56;
constexpr uint32_t MAX_CONTROL = 1024u * 1024u;
constexpr uint32_t MAX_VIDEO = 64u * 1024u;
constexpr uint16_t DEFAULT_TCP_PORT = 9000;
/** Hard cap on the shared queue; the slowest desktop is forced past this. */
constexpr size_t kMaxRxQueue = 512;
/** How many whole frames one desktop may lag before stale ones are skipped. */
constexpr size_t kVideoPrefixSize = 20;
constexpr size_t kVideoFrameIdOff = 16;
constexpr wchar_t kServiceName[] = L"RcRelay";
constexpr wchar_t kServiceDisplay[] = L"RemoteApp RC Relay";

SOCKET g_tcp_listen = INVALID_SOCKET;
SOCKET g_udp_listen = INVALID_SOCKET;
std::atomic<bool> g_stop{false};
SERVICE_STATUS_HANDLE g_status_handle = nullptr;
SERVICE_STATUS g_status{};
uint16_t g_tcp_port = DEFAULT_TCP_PORT;
std::string g_stun_url = "stun:stun.l.google.com:19302";
std::string g_turn_url;
std::string g_turn_user;
std::string g_turn_pass;

void close_sock(SOCKET *s)
{
    if (s && *s != INVALID_SOCKET) {
        shutdown(*s, SD_BOTH);
        closesocket(*s);
        *s = INVALID_SOCKET;
    }
}

uint32_t read_be32(const uint8_t *p)
{
    return (uint32_t) p[0] << 24 | (uint32_t) p[1] << 16
            | (uint32_t) p[2] << 8 | (uint32_t) p[3];
}

void write_be32(uint8_t *p, uint32_t v)
{
    p[0] = (uint8_t) ((v >> 24) & 0xff);
    p[1] = (uint8_t) ((v >> 16) & 0xff);
    p[2] = (uint8_t) ((v >> 8) & 0xff);
    p[3] = (uint8_t) (v & 0xff);
}

bool send_all(SOCKET s, const void *data, size_t len)
{
    if (len == 0) {
        return true;
    }
    const char *p = static_cast<const char *>(data);
    size_t sent = 0;
    while (sent < len) {
        const int n = send(s, p + sent, static_cast<int>(len - sent), 0);
        if (n <= 0) {
            return false;
        }
        sent += static_cast<size_t>(n);
    }
    return true;
}

bool recv_exact(SOCKET s, void *data, size_t len)
{
    char *p = static_cast<char *>(data);
    size_t got = 0;
    while (got < len) {
        const int n = recv(s, p + got, static_cast<int>(len - got), 0);
        if (n <= 0) {
            return false;
        }
        got += static_cast<size_t>(n);
    }
    return true;
}

bool send_control(SOCKET s, const std::string &json)
{
    std::vector<uint8_t> packet(5 + json.size());
    packet[0] = FLAG_CONTROL;
    write_be32(packet.data() + 1, static_cast<uint32_t>(json.size()));
    memcpy(packet.data() + 5, json.data(), json.size());
    return send_all(s, packet.data(), packet.size());
}

bool read_control_frame(SOCKET s, std::vector<uint8_t> *payload)
{
    uint8_t header[5];
    if (!recv_exact(s, header, 5) || header[0] != FLAG_CONTROL) {
        return false;
    }
    const uint32_t len = read_be32(header + 1);
    if (len == 0 || len > MAX_CONTROL) {
        return false;
    }
    payload->resize(len);
    return recv_exact(s, payload->data(), len);
}

void set_tcp_opts(SOCKET s)
{
    BOOL yes = TRUE;
    setsockopt(s, IPPROTO_TCP, TCP_NODELAY, reinterpret_cast<char *>(&yes),
               sizeof(yes));
    setsockopt(s, SOL_SOCKET, SO_KEEPALIVE, reinterpret_cast<char *>(&yes),
               sizeof(yes));
    // A sender cannot have more unacknowledged data outstanding than its send
    // buffer, so on a long path the buffer is what limits throughput. A desktop
    // measured 392ms away was capped at 128KB / 0.392s = 327KB/s — less than the
    // phone sends during full-screen motion, which backed the stream up on a link
    // that was never full. 512KB carries a 400ms path at over 1MB/s; that is one
    // pipe's worth of data, so it adds no staleness the distance did not already
    // impose, and stale frames are shed from the shared queue before they are
    // written anyway.
    int snd = 512 * 1024;
    int rcv = 512 * 1024;
    setsockopt(s, SOL_SOCKET, SO_SNDBUF, reinterpret_cast<char *>(&snd), sizeof(snd));
    setsockopt(s, SOL_SOCKET, SO_RCVBUF, reinterpret_cast<char *>(&rcv), sizeof(rcv));
}

std::string peer_ip(SOCKET s)
{
    sockaddr_in addr {};
    int len = sizeof(addr);
    if (getpeername(s, reinterpret_cast<sockaddr *>(&addr), &len) != 0) {
        return "";
    }
    char ip[INET_ADDRSTRLEN] = {0};
    inet_ntop(AF_INET, &addr.sin_addr, ip, sizeof(ip));
    return ip;
}

std::string json_escape(const std::string &in)
{
    std::string out;
    for (unsigned char c : in) {
        if (c == '\\' || c == '"') {
            out += '\\';
        }
        out += static_cast<char>(c);
    }
    return out;
}

bool json_string(const char *json, const char *key, std::string *out)
{
    const std::string pat = std::string("\"") + key + "\"";
    const char *p = json;
    while (p && (p = strstr(p, pat.c_str())) != nullptr) {
        const char *after = p + pat.size();
        while (*after == ' ' || *after == '\t') {
            ++after;
        }
        if (*after != ':') {
            p += pat.size();
            continue;
        }
        ++after;
        while (*after == ' ' || *after == '\t') {
            ++after;
        }
        if (*after != '"') {
            return false;
        }
        ++after;
        out->clear();
        while (*after && *after != '"') {
            if (*after == '\\' && after[1]) {
                ++after;
            }
            out->push_back(*after++);
        }
        return !out->empty();
    }
    return false;
}

int socket_available(SOCKET s)
{
    u_long avail = 0;
    if (s == INVALID_SOCKET || ioctlsocket(s, FIONREAD, &avail) != 0) {
        return -1;
    }
    return static_cast<int>(avail);
}

// Directory that holds rc-relay.exe.
std::string exe_directory();

std::string trim_copy(std::string s)
{
    while (!s.empty() && (s.back() == '\n' || s.back() == '\r' || s.back() == ' '
                          || s.back() == '\t')) {
        s.pop_back();
    }
    size_t i = 0;
    while (i < s.size() && (s[i] == ' ' || s[i] == '\t')) {
        ++i;
    }
    return s.substr(i);
}

// webrtc.cfg next to the relay exe. Lines are key=value. stun is always sent.
// turn / turn_user / turn_pass are optional and used only when both peers need a relay.
void load_ice_config()
{
    const std::string path = exe_directory() + "\\webrtc.cfg";
    FILE *f = fopen(path.c_str(), "rb");
    if (!f) {
        return;
    }
    char line[512];
    while (fgets(line, sizeof(line), f)) {
        std::string text = trim_copy(line);
        if (text.empty() || text[0] == '#') {
            continue;
        }
        const size_t eq = text.find('=');
        if (eq == std::string::npos) {
            continue;
        }
        const std::string key = trim_copy(text.substr(0, eq));
        const std::string value = trim_copy(text.substr(eq + 1));
        if (value.empty()) {
            continue;
        }
        if (key == "stun") {
            g_stun_url = value;
        } else if (key == "turn") {
            g_turn_url = value;
        } else if (key == "turn_user") {
            g_turn_user = value;
        } else if (key == "turn_pass") {
            g_turn_pass = value;
        }
    }
    fclose(f);
}

std::string ice_servers_json()
{
    std::string json = "[{\"urls\":\"" + json_escape(g_stun_url) + "\"}";
    if (!g_turn_url.empty()) {
        json += ",{\"urls\":\"" + json_escape(g_turn_url) + "\"";
        if (!g_turn_user.empty()) {
            json += ",\"username\":\"" + json_escape(g_turn_user) + "\"";
            json += ",\"credential\":\"" + json_escape(g_turn_pass) + "\"";
        }
        json += "}";
    }
    json += "]";
    return json;
}

std::string pair_ok_json(const char *role, const std::string &agent_id,
                         const std::string &desktop_id, uint32_t session_id)
{
    return std::string("{\"type\":\"pair_ok\",\"role\":\"") + role
            + "\",\"agentId\":\"" + json_escape(agent_id)
            + "\",\"desktopId\":\"" + json_escape(desktop_id)
            + "\",\"sessionId\":" + std::to_string(session_id)
            + ",\"iceServers\":" + ice_servers_json() + "}";
}

bool packet_json_field(const std::vector<uint8_t> &packet, const char *key, std::string *out)
{
    if (packet.size() < 6 || packet[0] != FLAG_CONTROL) {
        return false;
    }
    std::string json(reinterpret_cast<const char *>(packet.data() + 5), packet.size() - 5);
    json.push_back('\0');
    return json_string(json.c_str(), key, out);
}

std::string exe_directory()
{
    char path[MAX_PATH];
    const DWORD n = GetModuleFileNameA(nullptr, path, MAX_PATH);
    if (n == 0 || n >= MAX_PATH) {
        return ".";
    }
    std::string p(path, n);
    const size_t slash = p.find_last_of("\\/");
    return slash == std::string::npos ? std::string(".") : p.substr(0, slash);
}

// True only when the peer really is gone. A false positive here closes a viewer
// that is still perfectly healthy, which the desktop reports as "Disconnected" and
// blacks the window out, so every ambiguous answer has to mean "still alive":
// select() reports a socket readable on transient conditions as well as on data,
// and a recv() that then answers WSAEWOULDBLOCK means nothing more than "nothing to
// read right now".
bool socket_peer_gone(SOCKET s)
{
    const int avail = socket_available(s);
    if (avail > 0) {
        return false;
    }
    if (avail < 0) {
        return WSAGetLastError() != WSAEWOULDBLOCK;
    }
    fd_set rfds;
    FD_ZERO(&rfds);
    FD_SET(s, &rfds);
    timeval tv {};
    const int n = select(0, &rfds, nullptr, nullptr, &tv);
    if (n <= 0) {
        // n < 0 is a select() failure, not evidence about the peer.
        return false;
    }
    char peek = 0;
    const int r = recv(s, &peek, 1, MSG_PEEK);
    if (r > 0) {
        return false;
    }
    if (r == 0) {
        return true; // orderly shutdown
    }
    const int err = WSAGetLastError();
    return err != WSAEWOULDBLOCK && err != WSAEINTR && err != WSAEINPROGRESS;
}

// False on a closed or broken socket only. This drives both the agent and the viewer
// receive loops, so answering false on a socket that is merely idle retires a live
// phone (and with it every desktop watching it) while the phone still believes it is
// connected — the exact shape of "the desktop went black but the phone log is clean".
bool pull_available(SOCKET s, std::vector<uint8_t> *rx)
{
    const int avail = socket_available(s);
    if (avail == 0) {
        return true;
    }
    if (avail < 0) {
        return WSAGetLastError() == WSAEWOULDBLOCK;
    }
    const size_t off = rx->size();
    rx->resize(off + static_cast<size_t>(avail));
    const int n = recv(s, reinterpret_cast<char *>(rx->data() + off), avail, 0);
    if (n > 0) {
        rx->resize(off + static_cast<size_t>(n));
        return true;
    }
    rx->resize(off);
    if (n == 0) {
        return false; // orderly shutdown
    }
    const int err = WSAGetLastError();
    return err == WSAEWOULDBLOCK || err == WSAEINTR || err == WSAEINPROGRESS;
}

bool pop_one_packet(std::vector<uint8_t> *rx, std::vector<uint8_t> *packet)
{
    while (rx->size() >= 5) {
        const uint8_t flag = (*rx)[0];
        if (flag != FLAG_CONTROL && flag != FLAG_VIDEO) {
            rx->erase(rx->begin());
                continue;
            }
        const uint32_t len = read_be32(rx->data() + 1);
        const uint32_t max = flag == FLAG_VIDEO ? MAX_VIDEO : MAX_CONTROL;
        if (len == 0 || len > max) {
            rx->erase(rx->begin());
                continue;
            }
        const size_t need = 5 + static_cast<size_t>(len);
        if (rx->size() < need) {
            return false;
        }
        packet->assign(rx->begin(), rx->begin() + static_cast<std::ptrdiff_t>(need));
        rx->erase(rx->begin(), rx->begin() + static_cast<std::ptrdiff_t>(need));
        return true;
    }
    return false;
}

bool video_chunk_info(const std::vector<uint8_t> &packet, uint32_t *fid,
                      int *chunk_index, int *chunk_count)
{
    if (packet.size() < 5 + kVideoPrefixSize || packet[0] != FLAG_VIDEO) {
        return false;
    }
    const uint8_t *p = packet.data() + 5;
    *chunk_index = static_cast<int>(p[8]) << 8 | static_cast<int>(p[9]);
    *chunk_count = static_cast<int>(p[10]) << 8 | static_cast<int>(p[11]);
    *fid = read_be32(p + kVideoFrameIdOff);
    return true;
}

// An assembled video packet is the first chunk (5-byte header + 20-byte video
// prefix) followed by the elementary stream, so codec bytes start at this offset.
constexpr size_t kVideoSliceOffset = 5 + kVideoPrefixSize;

bool stream_ptr(const std::vector<uint8_t> &data, const uint8_t **p, size_t *n)
{
    if (data.size() <= kVideoSliceOffset) {
        return false;
    }
    *p = data.data() + kVideoSliceOffset;
    *n = data.size() - kVideoSliceOffset;
    return true;
}

// H.264 Annex B streams open with a 00 00 01 / 00 00 00 01 start code; JPEG opens
// with FF D8. Telling them apart lets the relay drop JPEG freely but keep H.264
// GOPs intact.
bool is_annexb(const std::vector<uint8_t> &data)
{
    const uint8_t *p = nullptr;
    size_t n = 0;
    if (!stream_ptr(data, &p, &n)) {
        return false;
    }
    if (n >= 4 && p[0] == 0x00 && p[1] == 0x00 && p[2] == 0x00 && p[3] == 0x01) {
        return true;
    }
    return n >= 3 && p[0] == 0x00 && p[1] == 0x00 && p[2] == 0x01;
}

// A whole-screen JPEG (FF D8). This is the only kind of frame a desktop can adopt
// with no history, so it is the only point a backlog may be shed at: the phone's
// other frames are 'RCT1' tile updates, which paint over part of the picture the
// desktop already has. Dropping one of those leaves that region showing something
// old until it happens to change again.
bool is_whole_picture(const std::vector<uint8_t> &data)
{
    const uint8_t *p = nullptr;
    size_t n = 0;
    if (!stream_ptr(data, &p, &n)) {
        return false;
    }
    return n >= 2 && p[0] == 0xFF && p[1] == 0xD8;
}

// A keyframe access unit starts with SPS/PPS (the phone prepends codec config) or
// an IDR slice — the only points where the H.264 stream can restart cleanly.
bool is_keyframe(const std::vector<uint8_t> &data)
{
    const uint8_t *p = nullptr;
    size_t n = 0;
    if (!stream_ptr(data, &p, &n)) {
        return false;
    }
    size_t off = 0;
    if (n >= 4 && p[0] == 0x00 && p[1] == 0x00 && p[2] == 0x00 && p[3] == 0x01) {
        off = 4;
    } else if (n >= 3 && p[0] == 0x00 && p[1] == 0x00 && p[2] == 0x01) {
        off = 3;
    } else {
        return false;
    }
    if (off >= n) {
        return false;
    }
    const int t = p[off] & 0x1F;
    return t == 7 || t == 8 || t == 5;
}

/** Shared, immutable packet bytes: one frame can go to many desktops for free. */
using Payload = std::shared_ptr<const std::vector<uint8_t>>;

Payload make_payload(std::vector<uint8_t> bytes)
{
    return std::make_shared<const std::vector<uint8_t>>(std::move(bytes));
}

Payload control_payload(const char *json)
{
    const size_t n = strlen(json);
    std::vector<uint8_t> pkt(5 + n);
    pkt[0] = FLAG_CONTROL;
    write_be32(pkt.data() + 1, static_cast<uint32_t>(n));
    memcpy(pkt.data() + 5, json, n);
    return make_payload(std::move(pkt));
}

struct DesktopSlot {
    SOCKET sock = INVALID_SOCKET;
    std::string ip;
};

/**
 * One packet the relay took off a phone socket: either a control packet, or every
 * chunk of one complete frame back to back. Frames are only queued once whole, so
 * dropping a stale entry can never truncate a frame a desktop is assembling.
 */
struct RxPacket {
    Payload data;
    std::string agent_id;
    uint64_t seq = 0;
    uint32_t frame_id = 0;
    bool video = false;
};

struct AgentSlot {
    SOCKET sock = INVALID_SOCKET;
    std::string id;
    std::string device;
    std::string model;
    std::string os;
    std::string serial;
    std::string ip;
    uint32_t session_id = 0;
    std::vector<uint8_t> register_frame;
    HANDLE done = nullptr;
    /** Serialises writes to the phone: every desktop forwards its input here. */
    std::mutex io_mu;

    /** Receive state, touched only by the single receive thread. */
    std::vector<uint8_t> rx_buf;
    std::vector<uint8_t> staging;
    uint32_t staging_fid = 0;
    int staging_next_chunk = 0;
    int staging_chunk_count = 0;
    bool staging_active = false;

    /** Newest complete frame, replayed to a desktop that opens its window later. */
    std::mutex last_mu;
    Payload last_frame;

    std::atomic<bool> alive{true};
};

/** One connected desktop: its own send thread, its own cursor into the queue. */
struct Viewer {
    SOCKET sock = INVALID_SOCKET;
    std::string desktop_id;
    std::string agent_id;
    std::shared_ptr<AgentSlot> agent;
    /** Next shared-queue sequence this desktop should look at. Guarded by rx_mu_. */
    uint64_t cursor = 0;
    std::mutex mu;
    /** Handshake / seed packets that must go out ahead of queue traffic. */
    std::deque<Payload> pending;
    std::atomic<bool> watching{false};
    std::atomic<bool> stop{false};
    std::thread send_th;
};

void reset_staging(AgentSlot *agent)
{
    agent->staging.clear();
    agent->staging_fid = 0;
    agent->staging_next_chunk = 0;
    agent->staging_chunk_count = 0;
    agent->staging_active = false;
}

class RelayHub {
public:
    static RelayHub &instance()
    {
        static RelayHub hub;
        return hub;
    }

    /** Starts the one and only phone receive thread. Safe to call repeatedly. */
    void start_rx()
    {
        std::call_once(rx_once_, [this]() {
            std::thread([this]() { run_mobile_rx(); }).detach();
        });
    }

    void handle_tcp(SOCKET sock)
    {
            std::vector<uint8_t> payload;
            if (!read_control_frame(sock, &payload)) {
            close_sock(&sock);
            return;
            }
            payload.push_back(0);
            const char *json = reinterpret_cast<char *>(payload.data());
            std::string type;
            std::string role;
            json_string(json, "type", &type);
            json_string(json, "role", &role);
            if (type == "hello" && role == "desktop") {
            handle_desktop(sock);
            } else if (type == "hello" && role == "attach") {
                std::string agent_id;
            std::string desktop_id;
                json_string(json, "agentId", &agent_id);
            json_string(json, "desktopId", &desktop_id);
            handle_attach(sock, agent_id, desktop_id);
            } else if (type == "register" || (type == "hello" && role == "agent")) {
                payload.pop_back();
                std::vector<uint8_t> frame(5 + payload.size());
                frame[0] = FLAG_CONTROL;
                write_be32(frame.data() + 1, static_cast<uint32_t>(payload.size()));
                memcpy(frame.data() + 5, payload.data(), payload.size());
            const std::string json_copy(reinterpret_cast<char *>(payload.data()),
                                      payload.size());
                handle_agent(sock, json_copy.c_str(), frame);
        }
        close_sock(&sock);
    }

private:
    std::mutex tcp_mutex_;
    std::vector<DesktopSlot *> desktops_;
    std::map<std::string, std::shared_ptr<AgentSlot>> agents_;
    std::atomic<uint32_t> next_session_{100};

    std::mutex viewers_mu_;
    std::vector<std::shared_ptr<Viewer>> viewers_;

    // The shared queue every desktop thread reads from.
    std::mutex rx_mu_;
    std::deque<RxPacket> rx_q_;
    uint64_t rx_seq_ = 0;

    std::once_flag rx_once_;
    /** Bumped once per receive pass; teardown waits on it before closing a socket. */
    std::atomic<uint64_t> rx_epoch_{0};

    // Forward one SDP or ICE packet from a phone to the desktop it names.
    // A missing desktopId goes to every desktop currently paired with this phone.
    void route_agent_signal(const std::string &agent_id, const std::vector<uint8_t> &packet)
    {
        std::string desktop_id;
        packet_json_field(packet, "desktopId", &desktop_id);
        for (auto &viewer : viewers_of(agent_id)) {
            if (!desktop_id.empty() && viewer->desktop_id != desktop_id) {
                continue;
            }
            std::lock_guard<std::mutex> lock(viewer->mu);
            if (viewer->stop.load() || viewer->sock == INVALID_SOCKET) {
                continue;
            }
            if (!send_all(viewer->sock, packet.data(), packet.size())) {
                viewer->stop.store(true);
            }
        }
    }

    // ---------------------------------------------------------------- receive side

    /**
     * The single thread that reads every phone. It multiplexes all agent sockets
     * with select(), reassembles whole frames per phone, and appends them to the
     * shared queue; the per-desktop threads pick their own packets from there.
     */
    void run_mobile_rx()
    {
        std::vector<std::shared_ptr<AgentSlot>> live;
        std::vector<uint8_t> packet;
        while (!g_stop.load()) {
            rx_epoch_.fetch_add(1);
            live.clear();
            {
                std::lock_guard<std::mutex> lock(tcp_mutex_);
                for (auto &kv : agents_) {
                    if (kv.second->alive.load() && kv.second->sock != INVALID_SOCKET) {
                        live.push_back(kv.second);
                    }
                }
            }
            if (live.empty()) {
                Sleep(2);
                continue;
            }

            fd_set rfds;
            FD_ZERO(&rfds);
            size_t added = 0;
            for (auto &a : live) {
                if (added >= FD_SETSIZE) {
                    break;
                }
                FD_SET(a->sock, &rfds);
                ++added;
            }
            // Short timeout so a phone that registers mid-wait joins the set quickly.
            timeval tv {};
            tv.tv_usec = 2000;
            if (select(0, &rfds, nullptr, nullptr, &tv) <= 0) {
                continue;
            }

            for (auto &a : live) {
                if (a->sock == INVALID_SOCKET || !FD_ISSET(a->sock, &rfds)) {
                    continue;
                }
                // Readable with nothing pending means the phone closed the socket.
                if (socket_available(a->sock) <= 0 || !pull_available(a->sock, &a->rx_buf)) {
                    mark_agent_dead(a.get());
                    continue;
                }
                while (pop_one_packet(&a->rx_buf, &packet)) {
                    if (packet.empty()) {
                        continue;
                    }
                    if (packet[0] == FLAG_VIDEO) {
                        // Distant peers often stay in ICE "connecting". Until the
                        // data channel opens, the phone sends the same JPEG frames
                        // on this socket so the desktop still gets a picture.
                        RxPacket item;
                        item.agent_id = a->id;
                        if (assemble(a.get(), std::move(packet), &item)) {
                            enqueue_rx(std::move(item));
                        }
                        continue;
                    }
                    std::string type;
                    if (!packet_json_field(packet, "type", &type)) {
                        continue;
                    }
                    if (type == "sdp" || type == "ice") {
                        route_agent_signal(a->id, packet);
                    }
                }
            }
        }
    }

    /**
     * Video chunks are staged until the frame is whole, then handed back as one
     * packet. Control packets pass straight through. Staging lives on the agent and
     * is only ever touched by the receive thread.
     */
    bool assemble(AgentSlot *agent, std::vector<uint8_t> packet, RxPacket *out)
    {
        if (packet.empty()) {
            return false;
        }
        uint32_t fid = 0;
        int chunk_index = 0;
        int chunk_count = 0;
        if (!video_chunk_info(packet, &fid, &chunk_index, &chunk_count)) {
            out->data = make_payload(std::move(packet));
            out->frame_id = 0;
            out->video = false;
            return true;
        }
        if (chunk_count < 1 || chunk_index < 0 || chunk_index >= chunk_count) {
            reset_staging(agent);
            return false;
        }
        if (!agent->staging_active
                || agent->staging_fid != fid
                || agent->staging_next_chunk != chunk_index) {
            if (chunk_index != 0) {
                reset_staging(agent);
                return false;
            }
            reset_staging(agent);
            agent->staging_active = true;
            agent->staging_fid = fid;
            agent->staging_chunk_count = chunk_count;
        }
        agent->staging.insert(agent->staging.end(), packet.begin(), packet.end());
        agent->staging_next_chunk = chunk_index + 1;
        if (agent->staging_next_chunk < agent->staging_chunk_count) {
            return false;
        }
        out->data = make_payload(std::move(agent->staging));
        out->frame_id = fid;
        out->video = true;
        reset_staging(agent);
        return true;
    }

    void enqueue_rx(RxPacket item)
    {
        if (!item.data || item.data->empty()) {
            return;
        }
        // Snapshot the desktops first: taking viewers_mu_ inside rx_mu_ would invert
        // the lock order used everywhere else.
        std::vector<std::shared_ptr<Viewer>> viewers;
        {
            std::lock_guard<std::mutex> lock(viewers_mu_);
            viewers = viewers_;
        }
        std::lock_guard<std::mutex> lock(rx_mu_);
        item.seq = rx_seq_++;
        rx_q_.push_back(std::move(item));
        trim_rx_locked(viewers);
    }

    /**
     * Called with rx_mu_ held. Frees what every desktop has already passed, then
     * enforces the hard cap by pulling lagging cursors forward, so one stalled
     * desktop can never pin the queue in memory.
     */
    void trim_rx_locked(const std::vector<std::shared_ptr<Viewer>> &viewers)
    {
        uint64_t oldest_needed = rx_seq_;
        for (const auto &v : viewers) {
            oldest_needed = std::min(oldest_needed, v->cursor);
        }
        while (!rx_q_.empty() && rx_q_.front().seq < oldest_needed) {
            rx_q_.pop_front();
        }
        while (rx_q_.size() > kMaxRxQueue) {
            rx_q_.pop_front();
        }
        if (rx_q_.empty()) {
            return;
        }
        const uint64_t head = rx_q_.front().seq;
        for (const auto &v : viewers) {
            if (v->cursor < head) {
                v->cursor = head;
            }
        }
    }

    void drop_agent_packets(const std::string &agent_id)
    {
        std::lock_guard<std::mutex> lock(rx_mu_);
        for (auto it = rx_q_.begin(); it != rx_q_.end();) {
            it = (it->agent_id == agent_id) ? rx_q_.erase(it) : it + 1;
        }
    }

    // ------------------------------------------------------------------- send side

    /**
     * Pick the next packet this desktop must send. Walks the shared queue from the
     * desktop's cursor, ignores packets from other phones, and skips stale video so
     * a desktop that fell behind jumps to the current picture instead of replaying
     * old frames. H.264 is skipped only when a newer keyframe exists, because
     * dropping mid-GOP would corrupt every following frame.
     */
    bool take_for_viewer(Viewer *viewer, Payload *out)
    {
        std::lock_guard<std::mutex> lock(rx_mu_);
        size_t idx = 0;
        while (idx < rx_q_.size() && rx_q_[idx].seq < viewer->cursor) {
            ++idx;
        }
        for (; idx < rx_q_.size(); ++idx) {
            const RxPacket &item = rx_q_[idx];
            viewer->cursor = item.seq + 1;
            if (item.agent_id != viewer->agent_id) {
                continue;
            }
            if (item.video) {
                if (!viewer->watching.load()) {
                    continue;
                }
                if (is_annexb(*item.data)) {
                    if (newer_keyframe_locked(idx, viewer->agent_id)) {
                        continue;
                    }
                } else if (newer_whole_picture_locked(idx, viewer->agent_id)) {
                    continue;
                }
            }
            *out = item.data;
            return true;
        }
        viewer->cursor = rx_seq_;
        return false;
    }

    // A desktop that has fallen behind should jump to the newest complete picture:
    // everything queued before one is history, and forwarding it only pushes the
    // current picture further back.
    bool newer_whole_picture_locked(size_t idx, const std::string &agent_id) const
    {
        for (size_t j = idx + 1; j < rx_q_.size(); ++j) {
            if (rx_q_[j].video && rx_q_[j].agent_id == agent_id
                    && is_whole_picture(*rx_q_[j].data)) {
                return true;
            }
        }
        return false;
    }

    bool newer_keyframe_locked(size_t idx, const std::string &agent_id) const
    {
        for (size_t j = idx + 1; j < rx_q_.size(); ++j) {
            if (rx_q_[j].video && rx_q_[j].agent_id == agent_id
                    && is_keyframe(*rx_q_[j].data)) {
                return true;
            }
        }
        return false;
    }

    void run_viewer_send(std::shared_ptr<Viewer> viewer)
    {
        while (!g_stop.load() && !viewer->stop.load()) {
            Payload out;
            {
                std::lock_guard<std::mutex> lock(viewer->mu);
                if (!viewer->pending.empty()) {
                    out = std::move(viewer->pending.front());
                    viewer->pending.pop_front();
                }
            }
            if (!out && !take_for_viewer(viewer.get(), &out)) {
                Sleep(1);
                continue;
            }
            if (!out || out->empty()) {
                continue;
            }
            if (!send_all(viewer->sock, out->data(), out->size())) {
                viewer->stop.store(true);
                break;
            }
        }
    }

    // ------------------------------------------------------------ viewer registry

    void add_viewer(const std::shared_ptr<Viewer> &viewer)
    {
        std::lock_guard<std::mutex> lock(viewers_mu_);
        viewers_.push_back(viewer);
    }

    void remove_viewer(const std::shared_ptr<Viewer> &viewer)
    {
        std::lock_guard<std::mutex> lock(viewers_mu_);
        for (auto it = viewers_.begin(); it != viewers_.end(); ++it) {
            if (*it == viewer) {
                viewers_.erase(it);
                    break;
                }
            }
    }

    std::vector<std::shared_ptr<Viewer>> viewers_of(const std::string &agent_id)
    {
        std::vector<std::shared_ptr<Viewer>> out;
        std::lock_guard<std::mutex> lock(viewers_mu_);
        for (auto &v : viewers_) {
            if (v->agent_id == agent_id) {
                out.push_back(v);
            }
        }
        return out;
    }

    bool any_watching(const std::string &agent_id, const Viewer *except)
    {
        std::lock_guard<std::mutex> lock(viewers_mu_);
        for (auto &v : viewers_) {
            if (v.get() == except || v->agent_id != agent_id) {
                continue;
            }
            if (v->watching.load()) {
                return true;
            }
        }
        return false;
    }

    void close_viewers_of(const std::string &agent_id)
    {
        for (auto &v : viewers_of(agent_id)) {
            SOCKET s = INVALID_SOCKET;
            {
                std::lock_guard<std::mutex> lock(v->mu);
                v->stop.store(true);
                s = v->sock;
                v->sock = INVALID_SOCKET;
            }
            if (s != INVALID_SOCKET) {
                shutdown(s, SD_BOTH);
            }
        }
    }

    // -------------------------------------------------------------- phone commands

    /** Pause capture. Sent only when the last watching desktop stops. */
    void send_monitor_stop(AgentSlot *agent)
    {
        static const Payload stop = control_payload(
                "{\"type\":\"command\",\"action\":\"screen_monitor_stop\",\"payload\":{}}");
        std::lock_guard<std::mutex> io(agent->io_mu);
        send_all(agent->sock, stop->data(), stop->size());
    }

    /**
     * Wait until the receive thread has begun a fresh pass, so any in-flight
     * select()/recv() on the socket of the agent we just marked dead is finished and
     * the socket can be closed safely. One pass is a couple of milliseconds.
     */
    void wait_rx_released()
    {
        const uint64_t target = rx_epoch_.load() + 2;
        for (int i = 0; i < 200 && !g_stop.load() && rx_epoch_.load() < target; ++i) {
            Sleep(5);
        }
    }

    void mark_agent_dead(AgentSlot *agent)
    {
        agent->alive.store(false);
        if (agent->done) {
            SetEvent(agent->done);
        }
    }

    /** Extract the "action" of a desktop -> phone control packet, if it is one. */
    bool packet_control_action(const std::vector<uint8_t> &packet, std::string *action)
    {
        if (packet.size() < 5 || packet[0] != FLAG_CONTROL) {
            return false;
        }
        const std::string json(reinterpret_cast<const char *>(packet.data() + 5),
                               packet.size() - 5);
        return json_string(json.c_str(), "action", action);
    }

    /**
     * Route one packet coming from a desktop. SDP, ICE, and commands go to the
     * phone so a remote session still works while ICE stays on "connecting".
     * screen_monitor_stop is sent by the relay when the last watcher disconnects.
     */
    void handle_viewer_packet(AgentSlot *agent, Viewer *viewer,
                              const std::vector<uint8_t> &packet)
    {
        std::string type;
        if (!packet_json_field(packet, "type", &type)) {
            return;
        }
        // SDP, ICE, and desktop commands. Commands must cross the relay while ICE
        // is still "connecting", which is the usual state for a remote phone.
        // screen_monitor_stop is owned by the relay: it is sent only when the
        // last watcher leaves, so one desktop closing its window does not stop
        // capture for another desktop.
        if (type != "sdp" && type != "ice" && type != "command") {
            return;
        }
        if (type == "command") {
            std::string action;
            if (packet_json_field(packet, "action", &action)
                    && action == "screen_monitor_stop") {
                return;
            }
        }
        (void) viewer;
        std::lock_guard<std::mutex> io(agent->io_mu);
        if (!agent->alive.load() || agent->sock == INVALID_SOCKET) {
            return;
        }
        send_all(agent->sock, packet.data(), packet.size());
    }

    /** Show a desktop the latest picture at once instead of a blank wait. */
    void seed_last_frame(AgentSlot *agent, Viewer *viewer)
    {
        Payload frame;
        {
            std::lock_guard<std::mutex> lock(agent->last_mu);
            frame = agent->last_frame;
        }
        if (!frame || frame->empty()) {
            return;
        }
        std::lock_guard<std::mutex> lock(viewer->mu);
        viewer->pending.push_back(std::move(frame));
    }

    // ------------------------------------------------------------------ connections

    void handle_desktop(SOCKET sock)
    {
        DesktopSlot slot;
        slot.sock = sock;
        slot.ip = peer_ip(sock);

        std::vector<std::shared_ptr<AgentSlot>> agents;
        {
                        std::lock_guard<std::mutex> lock(tcp_mutex_);
            desktops_.push_back(&slot);
            for (auto &kv : agents_) {
                agents.push_back(kv.second);
            }
        }

        send_control(sock, "{\"type\":\"ack\",\"role\":\"desktop\"}");
        for (auto &agent : agents) {
            notify_desktop(&slot, agent.get(), true);
        }

        while (!g_stop.load()) {
            std::vector<uint8_t> payload;
            if (!read_control_frame(sock, &payload)) {
                break;
            }
        }

        std::lock_guard<std::mutex> lock(tcp_mutex_);
        for (auto it = desktops_.begin(); it != desktops_.end(); ++it) {
            if (*it == &slot) {
                desktops_.erase(it);
                        break;
                    }
                }
            }

    void handle_agent(SOCKET sock, const char *json,
                      const std::vector<uint8_t> &register_frame)
    {
        auto agent = std::make_shared<AgentSlot>();
        json_string(json, "id", &agent->id);
        if (agent->id.empty()) {
            return;
        }
        json_string(json, "device", &agent->device);
        json_string(json, "model", &agent->model);
        json_string(json, "os", &agent->os);
        json_string(json, "serial", &agent->serial);
        agent->ip = peer_ip(sock);
        agent->sock = sock;
        agent->session_id = next_session_.fetch_add(1);
        agent->register_frame = register_frame;
        agent->done = CreateEventA(nullptr, TRUE, FALSE, nullptr);

        std::shared_ptr<AgentSlot> old;
        std::vector<DesktopSlot *> desktops;
            {
                std::lock_guard<std::mutex> lock(tcp_mutex_);
            auto it = agents_.find(agent->id);
            if (it != agents_.end()) {
                old = it->second;
            }
            agents_[agent->id] = agent;
            desktops = desktops_;
        }
        if (old) {
            // Same id reconnecting: retire the stale registration and drop its
            // desktops so they re-attach to this fresh socket. Its own thread owns
            // closing that socket.
            mark_agent_dead(old.get());
            close_viewers_of(old->id);
        }

        {
            const std::string ack =
                    std::string("{\"type\":\"ack\",\"role\":\"agent\",\"clientId\":\"")
                    + json_escape(agent->id)
                    + "\",\"sessionId\":" + std::to_string(agent->session_id) + "}";
            std::lock_guard<std::mutex> io(agent->io_mu);
            send_control(sock, ack.c_str());
        }
        for (DesktopSlot *desktop : desktops) {
            notify_desktop(desktop, agent.get(), true);
        }

        // The shared receive thread reads this socket. Here we only own the
        // connection's lifetime and wait until it reports the phone gone.
        while (!g_stop.load() && agent->alive.load()) {
            WaitForSingleObject(agent->done, 100);
        }

        agent->alive.store(false);
        wait_rx_released();
        close_viewers_of(agent->id);
        // Let the desktops detach before the caller closes this socket: they may
        // still be forwarding input into it. Bounded so one wedged desktop cannot
        // hang the teardown.
        for (int i = 0; i < 200 && !viewers_of(agent->id).empty(); ++i) {
            Sleep(10);
        }
        drop_agent_packets(agent->id);

        bool gone = false;
        {
            std::lock_guard<std::mutex> lock(tcp_mutex_);
            auto it = agents_.find(agent->id);
            if (it != agents_.end() && it->second == agent) {
                agents_.erase(it);
                gone = true;
                desktops = desktops_;
            } else {
                desktops.clear();
            }
        }
        if (gone) {
            for (DesktopSlot *desktop : desktops) {
                notify_offline(desktop, agent->id);
            }
        }
        if (agent->done) {
            CloseHandle(agent->done);
            agent->done = nullptr;
        }
    }

    void handle_attach(SOCKET sock, const std::string &agent_id,
                       const std::string &desktop_id)
    {
        std::shared_ptr<AgentSlot> agent;
        {
            std::lock_guard<std::mutex> lock(tcp_mutex_);
            auto it = agents_.find(agent_id);
            if (it != agents_.end()) {
                agent = it->second;
            }
        }
        if (!agent || !agent->alive.load()) {
            send_control(sock, "{\"type\":\"error\",\"error\":\"agent not found\"}");
            return;
        }

        auto viewer = std::make_shared<Viewer>();
        viewer->sock = sock;
        viewer->desktop_id = desktop_id;
        viewer->agent_id = agent_id;
        viewer->agent = agent;
        {
            // Start at "now": queue history belongs to desktops that were already here.
            std::lock_guard<std::mutex> lock(rx_mu_);
            viewer->cursor = rx_seq_;
        }

        // Pairing is the whole job of this socket: confirm the phone is online,
        // hand both sides the same ICE servers, then shuttle SDP and ICE only.
        viewer->watching.store(true);

        // Retire an earlier viewer from this same desktop (a reconnect) so one
        // desktop never leaves dead sockets behind on this phone.
        std::shared_ptr<Viewer> stale;
        if (!desktop_id.empty()) {
            for (auto &v : viewers_of(agent_id)) {
                if (v->desktop_id == desktop_id) {
                    stale = v;
                    break;
                }
            }
        }
        add_viewer(viewer);
        viewer->send_th = std::thread([this, viewer] { run_viewer_send(viewer); });
        if (stale) {
            SOCKET s = INVALID_SOCKET;
            {
                std::lock_guard<std::mutex> lock(stale->mu);
                stale->stop.store(true);
                s = stale->sock;
                stale->sock = INVALID_SOCKET;
            }
            if (s != INVALID_SOCKET) {
                shutdown(s, SD_BOTH);
            }
        }

        {
            const std::string to_desktop = pair_ok_json(
                    "offerer", agent_id, desktop_id, agent->session_id);
            const std::string to_phone = pair_ok_json(
                    "answerer", agent_id, desktop_id, agent->session_id);
            char ack[96];
            snprintf(ack, sizeof(ack),
                     "{\"type\":\"ack\",\"role\":\"attach\",\"sessionId\":%u}",
                     agent->session_id);
            {
                // route_agent_signal writes this socket too.
                std::lock_guard<std::mutex> lock(viewer->mu);
                send_control(sock, to_desktop);
                send_control(sock, ack);
            }
            std::lock_guard<std::mutex> io(agent->io_mu);
            if (agent->alive.load() && agent->sock != INVALID_SOCKET) {
                send_control(agent->sock, to_phone);
            }
        }
        {
            // Each side learns the public address of the other's relay TCP socket
            // and connects there directly. Mouse packets use that socket.
            std::string phone_ip;
            std::string desk_ip;
            int phone_port = 0;
            int desk_port = 0;
            sockaddr_in addr {};
            int alen = sizeof(addr);
            if (agent->sock != INVALID_SOCKET
                && getpeername(agent->sock, reinterpret_cast<sockaddr *>(&addr), &alen) == 0) {
                char ip[INET_ADDRSTRLEN] = {0};
                inet_ntop(AF_INET, &addr.sin_addr, ip, sizeof(ip));
                phone_ip = ip;
                phone_port = ntohs(addr.sin_port);
            }
            alen = sizeof(addr);
            if (getpeername(sock, reinterpret_cast<sockaddr *>(&addr), &alen) == 0) {
                char ip[INET_ADDRSTRLEN] = {0};
                inet_ntop(AF_INET, &addr.sin_addr, ip, sizeof(ip));
                desk_ip = ip;
                desk_port = ntohs(addr.sin_port);
            }
            if (!phone_ip.empty() && phone_port > 0) {
                char json[160];
                snprintf(json, sizeof(json),
                         "{\"type\":\"tcp_peer\",\"ip\":\"%s\",\"port\":%d}",
                         phone_ip.c_str(), phone_port);
                std::lock_guard<std::mutex> lock(viewer->mu);
                send_control(sock, json);
            }
            if (!desk_ip.empty() && desk_port > 0 && agent->sock != INVALID_SOCKET) {
                char json[160];
                snprintf(json, sizeof(json),
                         "{\"type\":\"tcp_peer\",\"ip\":\"%s\",\"port\":%d}",
                         desk_ip.c_str(), desk_port);
                std::lock_guard<std::mutex> io(agent->io_mu);
                send_control(agent->sock, json);
            }
        }
        // This connection's own thread reads the desktop's signaling and forwards
        // SDP / ICE to the phone. Media is not accepted here.
        std::vector<uint8_t> rx;
        std::vector<uint8_t> packet;
        while (!g_stop.load() && !viewer->stop.load() && agent->alive.load()) {
            if (socket_peer_gone(sock)) {
                break;
            }
            if (!pull_available(sock, &rx)) {
                break;
            }
            bool any = false;
            while (pop_one_packet(&rx, &packet)) {
                any = true;
                handle_viewer_packet(agent.get(), viewer.get(), packet);
            }
            if (!any) {
                Sleep(1);
            }
        }

        viewer->stop.store(true);
        if (viewer->send_th.joinable()) {
            viewer->send_th.join();
        }
        {
            std::lock_guard<std::mutex> lock(viewer->mu);
            viewer->sock = INVALID_SOCKET; // handle_tcp owns closing the fd
        }
        remove_viewer(viewer);

        const bool was_watching = viewer->watching.exchange(false);
        if (was_watching && !any_watching(agent_id, viewer.get()) && agent->alive.load()) {
            send_monitor_stop(agent.get());
        }
    }

    void notify_desktop(DesktopSlot *desktop, AgentSlot *agent, bool online)
    {
        char json[896];
        snprintf(json, sizeof(json),
                 "{\"type\":\"%s\",\"id\":\"%s\",\"device\":\"%s\","
                 "\"model\":\"%s\",\"os\":\"%s\",\"serial\":\"%s\",\"ip\":\"%s\","
                 "\"sessionId\":%u}",
                 online ? "peer_online" : "peer_offline",
                 json_escape(agent->id).c_str(),
                 json_escape(agent->device).c_str(),
                 json_escape(agent->model).c_str(),
                 json_escape(agent->os).c_str(),
                 json_escape(agent->serial).c_str(),
                 json_escape(agent->ip).c_str(),
                 agent->session_id);
        send_control(desktop->sock, json);
    }

    void notify_offline(DesktopSlot *desktop, const std::string &agent_id)
    {
        char json[256];
        snprintf(json, sizeof(json),
                 "{\"type\":\"peer_offline\",\"id\":\"%s\"}",
                 json_escape(agent_id).c_str());
        send_control(desktop->sock, json);
    }
};

// UDP media relay. One thread receives datagrams and pushes them onto a queue.
// Another thread pops that queue and sends each datagram to the paired peer.
// Datagram: RCUD | kind(1=hello,2=data) | role(1=phone,2=desktop) | idLen | id
//            | hello: agentLen agentId | data: one app packet
constexpr uint16_t kUdpQueueCap = 1024;

struct UdpJob {
    sockaddr_storage to {};
    int to_len = 0;
    std::vector<uint8_t> bytes;
};

std::mutex g_udp_mu;
std::condition_variable g_udp_cv;
std::deque<UdpJob> g_udp_q;
std::mutex g_udp_peer_mu;

struct UdpEndpoint {
    sockaddr_storage addr {};
    int len = 0;
    std::string agent;
};

std::map<std::string, UdpEndpoint> g_udp_phones;
std::map<std::string, UdpEndpoint> g_udp_desktops;

void udp_enqueue(sockaddr_storage to, int to_len, std::vector<uint8_t> bytes)
{
    std::lock_guard<std::mutex> lock(g_udp_mu);
    if (g_udp_q.size() >= kUdpQueueCap) {
        g_udp_q.pop_front();
    }
    UdpJob job;
    job.to = to;
    job.to_len = to_len;
    job.bytes = std::move(bytes);
    g_udp_q.push_back(std::move(job));
    g_udp_cv.notify_one();
}

void udp_send_loop()
{
    while (!g_stop.load()) {
        UdpJob job;
        {
            std::unique_lock<std::mutex> lock(g_udp_mu);
            g_udp_cv.wait_for(lock, std::chrono::milliseconds(200), [] {
                return g_stop.load() || !g_udp_q.empty();
            });
            if (g_udp_q.empty()) {
                continue;
            }
            job = std::move(g_udp_q.front());
            g_udp_q.pop_front();
        }
        if (g_udp_listen == INVALID_SOCKET || job.to_len <= 0 || job.bytes.empty()) {
            continue;
        }
        sendto(g_udp_listen, reinterpret_cast<char *>(job.bytes.data()),
               static_cast<int>(job.bytes.size()), 0,
               reinterpret_cast<sockaddr *>(&job.to), job.to_len);
    }
}

void udp_recv_loop()
{
    while (!g_stop.load()) {
        uint8_t buf[2048];
        sockaddr_storage from {};
        int from_len = sizeof(from);
        const int n = recvfrom(g_udp_listen, reinterpret_cast<char *>(buf), sizeof(buf), 0,
                               reinterpret_cast<sockaddr *>(&from), &from_len);
        if (n < 6) {
            if (g_stop.load()) {
                break;
            }
            continue;
        }
        if (buf[0] != 'R' || buf[1] != 'C' || buf[2] != 'U' || buf[3] != 'D') {
            continue;
        }
        const uint8_t kind = buf[4];
        const uint8_t role = buf[5];
        if (n < 7) {
            continue;
        }
        const int id_len = buf[6];
        if (id_len <= 0 || 7 + id_len > n) {
            continue;
        }
        const std::string id(reinterpret_cast<char *>(buf + 7), id_len);
        int cursor = 7 + id_len;
        if (kind == 1) {
            std::string agent;
            if (role == 2) {
                if (cursor >= n) {
                    continue;
                }
                const int agent_len = buf[cursor++];
                if (agent_len < 0 || cursor + agent_len > n) {
                    continue;
                }
                agent.assign(reinterpret_cast<char *>(buf + cursor), agent_len);
            }
            UdpEndpoint ep;
            ep.addr = from;
            ep.len = from_len;
            ep.agent = agent;
            std::lock_guard<std::mutex> lock(g_udp_peer_mu);
            if (role == 1) {
                g_udp_phones[id] = ep;
            } else if (role == 2) {
                g_udp_desktops[id] = ep;
            }
            std::vector<uint8_t> ack(buf, buf + n);
            ack[4] = 3;
            udp_enqueue(from, from_len, std::move(ack));
            const std::string phone_id = (role == 1) ? id : agent;
            UdpEndpoint phone_ep;
            std::vector<UdpEndpoint> desks;
            if (!phone_id.empty()) {
                auto pit = g_udp_phones.find(phone_id);
                if (pit != g_udp_phones.end()) {
                    phone_ep = pit->second;
                    for (const auto &item : g_udp_desktops) {
                        if (item.second.agent == phone_id && item.second.len > 0) {
                            desks.push_back(item.second);
                        }
                    }
                }
            }
            auto pack_peer = [](const sockaddr_storage &addr, int len) {
                std::vector<uint8_t> pkt;
                if (len < (int) sizeof(sockaddr_in) || addr.ss_family != AF_INET) {
                    return pkt;
                }
                const auto *in = reinterpret_cast<const sockaddr_in *>(&addr);
                pkt = {'R', 'C', 'U', 'D', 4, 0, 0};
                const unsigned char *ip = reinterpret_cast<const unsigned char *>(&in->sin_addr);
                pkt.insert(pkt.end(), ip, ip + 4);
                pkt.push_back(static_cast<uint8_t>(ntohs(in->sin_port) >> 8));
                pkt.push_back(static_cast<uint8_t>(ntohs(in->sin_port) & 0xFF));
                return pkt;
            };
            if (phone_ep.len > 0) {
                for (const UdpEndpoint &desk : desks) {
                    std::vector<uint8_t> to_phone = pack_peer(desk.addr, desk.len);
                    std::vector<uint8_t> to_desk = pack_peer(phone_ep.addr, phone_ep.len);
                    if (!to_phone.empty()) {
                        udp_enqueue(phone_ep.addr, phone_ep.len, std::move(to_phone));
                    }
                    if (!to_desk.empty()) {
                        udp_enqueue(desk.addr, desk.len, std::move(to_desk));
                    }
                }
            }
            continue;
        }
        if (kind != 2 || cursor >= n) {
            continue;
        }
        std::vector<UdpEndpoint> targets;
        {
            std::lock_guard<std::mutex> lock(g_udp_peer_mu);
            if (role == 1) {
                auto phone = g_udp_phones.find(id);
                if (phone != g_udp_phones.end()) {
                    phone->second.addr = from;
                    phone->second.len = from_len;
                }
                for (const auto &item : g_udp_desktops) {
                    if (item.second.agent == id) {
                        targets.push_back(item.second);
                    }
                }
            } else if (role == 2) {
                auto desk = g_udp_desktops.find(id);
                if (desk == g_udp_desktops.end()) {
                    continue;
                }
                auto phone = g_udp_phones.find(desk->second.agent);
                if (phone == g_udp_phones.end()) {
                    continue;
                }
                targets.push_back(phone->second);
            }
        }
        for (const UdpEndpoint &target : targets) {
            udp_enqueue(target.addr, target.len,
                        std::vector<uint8_t>(buf, buf + n));
        }
    }
}

void start_udp_relay(uint16_t tcp_port)
{
    const uint16_t udp_port = 9000;
    (void) tcp_port;
    g_udp_listen = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (g_udp_listen == INVALID_SOCKET) {
        return;
    }
    BOOL yes = TRUE;
    setsockopt(g_udp_listen, SOL_SOCKET, SO_REUSEADDR,
               reinterpret_cast<char *>(&yes), sizeof(yes));
    sockaddr_in addr {};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = htonl(INADDR_ANY);
    addr.sin_port = htons(udp_port);
    if (bind(g_udp_listen, reinterpret_cast<sockaddr *>(&addr), sizeof(addr)) != 0) {
        close_sock(&g_udp_listen);
        return;
    }
    int rcv = 1024 * 1024;
    setsockopt(g_udp_listen, SOL_SOCKET, SO_RCVBUF, reinterpret_cast<char *>(&rcv), sizeof(rcv));
    std::thread(udp_recv_loop).detach();
    std::thread(udp_send_loop).detach();
}

void run_relay(uint16_t tcp_port)
{
    WSADATA wsa;
    if (WSAStartup(MAKEWORD(2, 2), &wsa) != 0) {
        return;
    }

    load_ice_config();
    g_tcp_port = tcp_port;
    g_tcp_listen = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
    if (g_tcp_listen == INVALID_SOCKET) {
        WSACleanup();
        return;
    }
    BOOL yes = TRUE;
    setsockopt(g_tcp_listen, SOL_SOCKET, SO_REUSEADDR,
               reinterpret_cast<char *>(&yes), sizeof(yes));
    sockaddr_in tcp_addr {};
    tcp_addr.sin_family = AF_INET;
    tcp_addr.sin_addr.s_addr = htonl(INADDR_ANY);
    tcp_addr.sin_port = htons(tcp_port);
    if (bind(g_tcp_listen, reinterpret_cast<sockaddr *>(&tcp_addr), sizeof(tcp_addr)) != 0
            || listen(g_tcp_listen, 128) != 0) {
        close_sock(&g_tcp_listen);
        WSACleanup();
        return;
    }

    RelayHub::instance().start_rx();
    start_udp_relay(tcp_port);

    while (!g_stop.load()) {
        sockaddr_in peer {};
        int plen = sizeof(peer);
        SOCKET client = accept(g_tcp_listen, reinterpret_cast<sockaddr *>(&peer), &plen);
        if (client == INVALID_SOCKET) {
            if (g_stop.load()) {
                break;
            }
            continue;
        }
        set_tcp_opts(client);
        std::thread([client]() { RelayHub::instance().handle_tcp(client); }).detach();
    }

    close_sock(&g_tcp_listen);
    close_sock(&g_udp_listen);
    g_udp_cv.notify_all();
    WSACleanup();
}

void report_service(DWORD state, DWORD exit_code = NO_ERROR)
{
    g_status.dwServiceType = SERVICE_WIN32_OWN_PROCESS;
    g_status.dwCurrentState = state;
    g_status.dwWin32ExitCode = exit_code;
    g_status.dwControlsAccepted =
            (state == SERVICE_START_PENDING) ? 0 : SERVICE_ACCEPT_STOP;
    g_status.dwWaitHint = (state == SERVICE_START_PENDING) ? 3000 : 0;
    if (g_status_handle) {
        SetServiceStatus(g_status_handle, &g_status);
    }
}

DWORD WINAPI service_ctrl(DWORD ctrl, DWORD, LPVOID, LPVOID)
{
    if (ctrl == SERVICE_CONTROL_STOP) {
        report_service(SERVICE_STOP_PENDING);
        g_stop.store(true);
        close_sock(&g_tcp_listen);
        close_sock(&g_udp_listen);
        g_udp_cv.notify_all();
    }
    return NO_ERROR;
}

VOID WINAPI service_main(DWORD, LPWSTR *)
{
    g_status_handle = RegisterServiceCtrlHandlerExW(kServiceName, service_ctrl, nullptr);
    report_service(SERVICE_START_PENDING);
    report_service(SERVICE_RUNNING);
    run_relay(g_tcp_port);
    report_service(SERVICE_STOPPED);
}

BOOL WINAPI console_ctrl(DWORD)
{
    g_stop.store(true);
    close_sock(&g_tcp_listen);
    close_sock(&g_udp_listen);
    g_udp_cv.notify_all();
    return TRUE;
}

std::wstring module_path()
{
    wchar_t path[MAX_PATH];
    GetModuleFileNameW(nullptr, path, MAX_PATH);
    return path;
}

int install_service()
{
    SC_HANDLE scm = OpenSCManagerW(nullptr, nullptr, SC_MANAGER_CREATE_SERVICE);
    if (!scm) {
        return 1;
    }
    std::wstring bin = L"\"" + module_path() + L"\" --service";
    SC_HANDLE svc = CreateServiceW(
            scm, kServiceName, kServiceDisplay, SERVICE_ALL_ACCESS,
            SERVICE_WIN32_OWN_PROCESS, SERVICE_AUTO_START, SERVICE_ERROR_NORMAL,
            bin.c_str(), nullptr, nullptr, nullptr, nullptr, nullptr);
    if (!svc) {
        CloseServiceHandle(scm);
        return GetLastError() == ERROR_SERVICE_EXISTS ? 0 : 1;
    }
    StartServiceW(svc, 0, nullptr);
    CloseServiceHandle(svc);
    CloseServiceHandle(scm);
    return 0;
}

int uninstall_service()
{
    SC_HANDLE scm = OpenSCManagerW(nullptr, nullptr, SC_MANAGER_CONNECT);
    if (!scm) {
        return 1;
    }
    SC_HANDLE svc = OpenServiceW(scm, kServiceName, SERVICE_STOP | DELETE);
    if (!svc) {
        CloseServiceHandle(scm);
        return 0;
    }
    SERVICE_STATUS st {};
    ControlService(svc, SERVICE_CONTROL_STOP, &st);
    DeleteService(svc);
    CloseServiceHandle(svc);
    CloseServiceHandle(scm);
    return 0;
}

}  // namespace

int main(int argc, char **argv)
{
    bool as_service = false;
    for (int i = 1; i < argc; ++i) {
        if (!strcmp(argv[i], "install")) {
            return install_service();
        }
        if (!strcmp(argv[i], "uninstall")) {
            return uninstall_service();
        }
        if (!strcmp(argv[i], "--service")) {
            as_service = true;
            continue;
        }
        if (!strcmp(argv[i], "--port") && i + 1 < argc) {
            g_tcp_port = static_cast<uint16_t>(atoi(argv[++i]));
            continue;
        }
        return 1;
    }

    if (as_service) {
        SERVICE_TABLE_ENTRYW table[] = {
            {const_cast<LPWSTR>(kServiceName), service_main},
            {nullptr, nullptr},
        };
        return StartServiceCtrlDispatcherW(table) ? 0 : 1;
    }

    SetConsoleCtrlHandler(console_ctrl, TRUE);
    run_relay(g_tcp_port);
    return 0;
}
