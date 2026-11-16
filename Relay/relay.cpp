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
#include <functional>
#include <map>
#include <random>
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
    std::string id;
    // Shown on the phone's dashboard when this controller takes a mobile.
    std::string name;
    std::string detail;
    std::string device;
    // Last time anything arrived on this control socket. A controller that stops
    // answering has to be noticed quickly, because the mobile it holds stays
    // unavailable to everyone else until this socket is declared dead.
    std::atomic<uint64_t> last_rx_ms{0};
    /** One control frame at a time. List updates are written from several threads. */
    std::mutex send_mu;
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
    /** User stopped sharing, or the one-hour limit elapsed. Desktops drop the row. */
    std::atomic<bool> ended{false};

    /**
     * The phone has screen capture and its accessibility service up and is able to
     * serve a session right now. Starts false and is only ever set by the phone
     * saying so: a mobile that cannot stream or cannot be clicked must not appear
     * on anyone's list, and a registered socket on its own proves neither.
     */
    std::atomic<bool> ready{false};
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

/** A controller may hold a mobile for this long before the relay ends the session. */
constexpr uint64_t kSessionLimitMs = 60ULL * 60 * 1000;

/**
 * A control socket silent for this long is treated as a dead controller. The
 * desktop pings every few seconds, so this only trips on a real loss — and it has
 * to be short, because the mobile it holds cannot be handed to anyone else until
 * the loss is noticed.
 */
constexpr uint64_t kDesktopIdleMs = 12000;

uint64_t now_ms()
{
    return uint64_t(std::chrono::duration_cast<std::chrono::milliseconds>(
                        std::chrono::steady_clock::now().time_since_epoch())
                        .count());
}

/**
 * One sharing session of one mobile, from the moment the user starts sharing until
 * that offer is used up. The relay owns this because it is the only party that sees
 * every controller.
 *
 * [share_id] identifies the mobile's sharing session itself. It is generated once
 * when the user starts sharing and is resent unchanged on every reconnect, so a
 * dropped phone socket resumes the same session while an explicit restart creates a
 * new one — which is what earns a fresh hour.
 *
 * [consumed] is the "no second chance" rule: once the owner is lost without a clean
 * release, or the hour runs out, this offer is over for everybody. Only the mobile
 * starting to share again clears it.
 */
struct ShareSession {
    std::string share_id;
    std::string owner;
    bool consumed = false;
    uint64_t deadline_ms = 0;
    /**
     * Minted fresh every time a controller takes this mobile, and handed to both
     * sides. The phone only obeys input stamped with the current key, which is what
     * actually revokes the previous controller: it may still hold a punched UDP or
     * direct-TCP path to the phone that the relay cannot reach in to close.
     */
    std::string control_key;
    /**
     * Phone socket this offer is bound to. A goodbye from an older socket must
     * not erase the offer a newer socket has already opened.
     */
    uint32_t slot_session = 0;
};

/** Unguessable enough to stop a stale controller replaying the previous key. */
std::string make_control_key()
{
    static std::mutex mu;
    static std::mt19937_64 rng(std::chrono::steady_clock::now().time_since_epoch().count()
                               ^ uint64_t(GetCurrentProcessId()));
    uint64_t a = 0;
    uint64_t b = 0;
    {
        std::lock_guard<std::mutex> lock(mu);
        a = rng();
        b = rng();
    }
    char buf[40];
    snprintf(buf, sizeof(buf), "%016llx%016llx",
             static_cast<unsigned long long>(a), static_cast<unsigned long long>(b));
    return std::string(buf);
}

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
            handle_desktop(sock, json);
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
    std::mutex claim_mu_;
    std::map<std::string, ShareSession> sessions_;
    // Share ids the user already stopped. A socket that comes back with the same
    // id must not reopen the offer; only a new Start (a different share id) does.
    std::map<std::string, std::string> stopped_share_;
    /** Serialises list broadcasts so every controller sees them in the same order. */
    std::mutex announce_mu_;
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
        uint64_t next_expiry_check = 0;
        while (!g_stop.load()) {
            rx_epoch_.fetch_add(1);
            const uint64_t now = now_ms();
            if (now >= next_expiry_check) {
                next_expiry_check = now + 1000;
                expire_sessions();
                expire_desktops();
            }
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
                // Packets already pulled — including session_end sitting in front
                // of the FIN — have to be handled before the socket is retired,
                // or the desktop never hears that sharing stopped.
                const bool peer_gone = socket_available(a->sock) <= 0
                        || !pull_available(a->sock, &a->rx_buf);
                while (pop_one_packet(&a->rx_buf, &packet)) {
                    if (packet.empty()) {
                        continue;
                    }
                    if (packet[0] == FLAG_VIDEO) {
                        // The phone sends every frame on this socket. The viewer
                        // thread of whoever is attached right now is what reads
                        // the queue, so a controller that connects after another
                        // one pressed Stop gets the live picture instead of the
                        // address the previous punch happened to hold.
                        RxPacket item;
                        item.agent_id = a->id;
                        if (assemble(a.get(), std::move(packet), &item)) {
                            if (item.video && item.data && is_whole_picture(*item.data)) {
                                std::lock_guard<std::mutex> lock(a->last_mu);
                                a->last_frame = item.data;
                            }
                            enqueue_rx(std::move(item));
                        }
                        continue;
                    }
                    std::string type;
                    if (!packet_json_field(packet, "type", &type)) {
                        continue;
                    }
                    if (type == "session_end") {
                        // End the offer before the socket teardown waits. Until
                        // that wait finished, a controller still holding the
                        // preview re-attached and the window came back.
                        a->ended.store(true);
                        end_viewers(a->id);
                        {
                            std::lock_guard<std::mutex> lock(claim_mu_);
                            auto it = sessions_.find(a->id);
                            if (it != sessions_.end()
                                    && it->second.slot_session == a->session_id) {
                                remember_stopped_locked(a->id, it->second);
                                sessions_.erase(it);
                            }
                        }
                        publish_current(a->id);
                        mark_agent_dead(a.get());
                        continue;
                    }
                    if (type == "ready") {
                        std::string state;
                        packet_json_field(packet, "state", &state);
                        set_agent_ready(a, state == "ok");
                        continue;
                    }
                    if (type == "rename") {
                        rename_agent(a, packet);
                        continue;
                    }
                    if (type == "sdp" || type == "ice") {
                        route_agent_signal(a->id, packet);
                    }
                }
                if (peer_gone && a->alive.load()) {
                    mark_agent_dead(a.get());
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
        while (!g_stop.load()) {
            Payload out;
            {
                std::lock_guard<std::mutex> lock(viewer->mu);
                if (!viewer->pending.empty()) {
                    out = std::move(viewer->pending.front());
                    viewer->pending.pop_front();
                }
            }
            if (!out) {
                // preview_close is queued and then stop is set. Leave only
                // after that packet has been written, so the desktop window
                // closes instead of sitting on the last frame.
                if (viewer->stop.load()) {
                    break;
                }
                if (!take_for_viewer(viewer.get(), &out)) {
                    Sleep(1);
                    continue;
                }
            }
            if (!out || out->empty()) {
                if (viewer->stop.load()) {
                    break;
                }
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

    /**
     * The phone stopped sharing. Tell every open preview to close itself, let
     * that packet leave, then drop the socket. Closing the socket alone left
     * the desktop window up, waiting to attach again.
     */
    void end_viewers(const std::string &agent_id)
    {
        const Payload bye = control_payload("{\"type\":\"preview_close\"}");
        auto viewers = viewers_of(agent_id);
        for (auto &v : viewers) {
            std::lock_guard<std::mutex> lock(v->mu);
            v->pending.push_back(bye);
            v->stop.store(true);
        }
        for (int i = 0; i < 40; ++i) {
            bool pending = false;
            for (auto &v : viewers) {
                std::lock_guard<std::mutex> lock(v->mu);
                if (!v->pending.empty()) {
                    pending = true;
                }
            }
            if (!pending) {
                break;
            }
            Sleep(5);
        }
        close_viewers_of(agent_id);
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
            // Ownership is checked on every command, not only when the socket was
            // opened. A controller that has stopped, been replaced, or had its
            // claim expire keeps this socket for as long as TCP takes to notice,
            // and anything it sends in that window must not reach the phone.
            if (viewer && !owns_session(viewer->desktop_id, viewer->agent_id)) {
                return;
            }
        }
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

    void handle_desktop(SOCKET sock, const char *json)
    {
        DesktopSlot slot;
        slot.sock = sock;
        slot.ip = peer_ip(sock);
        json_string(json, "desktopId", &slot.id);
        json_string(json, "name", &slot.name);
        json_string(json, "detail", &slot.detail);
        json_string(json, "device", &slot.device);
        if (slot.name.empty()) {
            slot.name = slot.id.empty() ? "Support controller" : slot.id;
        }
        if (slot.device.empty()) {
            slot.device = "Remote desktop";
        }

        std::vector<std::shared_ptr<AgentSlot>> agents;
        {
            std::lock_guard<std::mutex> lock(tcp_mutex_);
            desktops_.push_back(&slot);
            for (auto &kv : agents_) {
                agents.push_back(kv.second);
            }
        }

        send_desktop_json(&slot, "{\"type\":\"ack\",\"role\":\"desktop\"}");
        for (auto &agent : agents) {
            // A phone already in someone else's preview stays off this table.
            // One claimed by this same desktop (a reconnect) is still listed.
            const std::string owner = claimed_by_desktop(agent->id);
            if (!owner.empty() && owner != slot.id) {
                continue;
            }
            // A spent offer is not on offer: it waits for the mobile to re-share.
            if (session_consumed(agent->id)) {
                continue;
            }
            // Nor is one whose phone cannot currently stream or take input.
            if (!agent->ready.load()) {
                continue;
            }
            notify_desktop(&slot, agent.get(), true);
        }

        slot.last_rx_ms.store(now_ms());
        while (!g_stop.load()) {
            std::vector<uint8_t> payload;
            if (!read_control_frame(sock, &payload)) {
                break;
            }
            slot.last_rx_ms.store(now_ms());
            payload.push_back(0);
            const char *msg = reinterpret_cast<char *>(payload.data());
            std::string type;
            std::string agent_id;
            json_string(msg, "type", &type);
            json_string(msg, "agentId", &agent_id);
            if (type == "claim") {
                request_claim(&slot, agent_id);
            } else if (type == "release") {
                const bool end_share = std::strstr(msg, "\"endShare\":true") != nullptr;
                release_claim(&slot, agent_id, end_share);
            } else if (type == "expire") {
                // The controller's own clock hit one hour. Same ending as the
                // relay's clock: this message is only a second path into it.
                if (owns_session(slot.id, agent_id)) {
                    end_share_session(agent_id, "one hour elapsed");
                }
            }
            // "ping" needs no reply: arriving at all is the liveness proof.
        }

        release_claims_of(&slot);
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
        std::string share_id;
        json_string(json, "shareId", &share_id);
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

        // A new shareId means the user started sharing again, which is the only
        // thing that reopens a spent offer and grants a fresh hour. The same
        // shareId is a dropped socket coming back, and must keep its session —
        // otherwise a network blip would hand out a new hour and let a second
        // controller in behind the first one's back. A share id that Stop
        // already spent must not come back as a fresh offer either: erasing
        // the session used to make the next register look brand new.
        bool offer_open = true;
        {
            std::lock_guard<std::mutex> lock(claim_mu_);
            auto stopped = stopped_share_.find(agent->id);
            if (stopped != stopped_share_.end() && !share_id.empty()
                    && stopped->second == share_id) {
                offer_open = false;
                agent->ended.store(true);
            } else {
                if (stopped != stopped_share_.end()) {
                    stopped_share_.erase(stopped);
                }
                ShareSession &s = sessions_[agent->id];
                if (share_id.empty() || s.share_id != share_id) {
                    s = ShareSession();
                    s.share_id = share_id;
                }
                s.slot_session = agent->session_id;
            }
        }

        {
            const std::string ack =
                    std::string("{\"type\":\"ack\",\"role\":\"agent\",\"clientId\":\"")
                    + json_escape(agent->id)
                    + "\",\"sessionId\":" + std::to_string(agent->session_id) + "}";
            std::lock_guard<std::mutex> io(agent->io_mu);
            send_control(sock, ack.c_str());
        }
        // Nothing is announced here. A socket is not a working mobile: the phone
        // sends its "ready" the moment capture and accessibility are both up, and
        // that is what puts it on the operators' lists.
        (void) desktops;
        if (!offer_open) {
            // This socket is the share the user already stopped. Tell the phone
            // again in case the first share_stop was lost, and do not put it
            // back on any list.
            send_to_agent_json(agent->id, "{\"type\":\"share_stop\"}");
        } else {
            // A dropped link comes back with the same share id, so the owner and key
            // are still here. The new socket starts with an empty key and would
            // refuse every input until the user Stop/Start. Hand the key back now.
            push_existing_owner(agent->id);
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
            // A phone that already registered again owns the offer now. This
            // socket's goodbye must not erase that offer or it vanishes from
            // every controller until one of them is restarted.
            bool still_ours = false;
            {
                std::lock_guard<std::mutex> lock(claim_mu_);
                auto it = sessions_.find(agent->id);
                still_ours = it != sessions_.end()
                        && it->second.slot_session == agent->session_id;
                if (still_ours && agent->ended.load()) {
                    remember_stopped_locked(agent->id, it->second);
                    sessions_.erase(it);
                }
            }
            if (!still_ours) {
                // Controller Stop already erased the offer. The row still has to
                // leave every list, the same way a handset session_end does.
                if (agent->ended.load()) {
                    for (DesktopSlot *desktop : desktops) {
                        notify_offline(desktop, agent->id, false, true, agent->session_id);
                    }
                } else {
                    publish_current(agent->id);
                }
            } else if (agent->ended.load()) {
                for (DesktopSlot *desktop : desktops) {
                    notify_offline(desktop, agent->id, false, true, agent->session_id);
                }
            } else {
                const std::string owner = claimed_by_desktop(agent->id);
                for (DesktopSlot *desktop : desktops) {
                    // Other operators already have the row hidden. A plain offline
                    // would put it back; keep it hidden until the preview closes.
                    if (!owner.empty() && desktop->id != owner) {
                        notify_offline(desktop, agent->id, true, false, agent->session_id);
                        continue;
                    }
                    notify_offline(desktop, agent->id, false, false, agent->session_id);
                }
            }
        } else {
            // Replaced by a newer socket. Publish that socket, not this one's end.
            publish_current(agent->id);
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
        // The claim is what decides who controls this phone, so it has to be
        // checked here too: a desktop that never got the grant must not be able to
        // reach the phone by opening an attach socket of its own.
        {
            std::lock_guard<std::mutex> lock(claim_mu_);
            auto it = sessions_.find(agent_id);
            const bool owns = it != sessions_.end() && !it->second.consumed
                    && !it->second.owner.empty() && it->second.owner == desktop_id;
            if (!owns) {
                send_control(sock, "{\"type\":\"error\",\"error\":\"not the session owner\"}");
                return;
            }
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
        // The latest whole frame, so the window is not blank until the next
        // capture. Live frames queued after this cursor follow it.
        seed_last_frame(agent.get(), viewer.get());
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
        send_desktop_json(desktop, json);
    }

    void notify_offline(DesktopSlot *desktop, const std::string &agent_id,
                        bool busy = false, bool ended = false,
                        uint32_t session_id = 0)
    {
        char json[512];
        if (ended) {
            snprintf(json, sizeof(json),
                     "{\"type\":\"peer_offline\",\"id\":\"%s\",\"ended\":true,"
                     "\"sessionId\":%u}",
                     json_escape(agent_id).c_str(), session_id);
        } else if (busy) {
            snprintf(json, sizeof(json),
                     "{\"type\":\"peer_offline\",\"id\":\"%s\",\"busy\":true,"
                     "\"sessionId\":%u}",
                     json_escape(agent_id).c_str(), session_id);
        } else {
            snprintf(json, sizeof(json),
                     "{\"type\":\"peer_offline\",\"id\":\"%s\",\"sessionId\":%u}",
                     json_escape(agent_id).c_str(), session_id);
        }
        send_desktop_json(desktop, json);
    }

    std::string claimed_by_desktop(const std::string &agent_id)
    {
        std::lock_guard<std::mutex> lock(claim_mu_);
        auto it = sessions_.find(agent_id);
        return it == sessions_.end() ? std::string() : it->second.owner;
    }

    /** True while this controller is the one holding this mobile right now. */
    bool owns_session(const std::string &desktop_id, const std::string &agent_id)
    {
        if (desktop_id.empty() || agent_id.empty()) {
            return false;
        }
        std::lock_guard<std::mutex> lock(claim_mu_);
        auto it = sessions_.find(agent_id);
        return it != sessions_.end() && !it->second.consumed
                && it->second.owner == desktop_id;
    }

    /** True once this sharing offer is spent: no controller may take it any more. */
    bool session_consumed(const std::string &agent_id)
    {
        std::lock_guard<std::mutex> lock(claim_mu_);
        auto it = sessions_.find(agent_id);
        return it != sessions_.end() && it->second.consumed;
    }

    /** Caller holds claim_mu_. Remembers the share id so the same Start cannot return. */
    void remember_stopped_locked(const std::string &agent_id, const ShareSession &session)
    {
        if (!session.share_id.empty()) {
            stopped_share_[agent_id] = session.share_id;
        }
    }

    void send_session_owner(const std::string &agent_id, const std::string &desktop_id,
                            const std::string &name, const std::string &detail,
                            const std::string &device, const std::string &control_key,
                            uint64_t remain_ms)
    {
        if (agent_id.empty() || control_key.empty()) {
            return;
        }
        char to_phone[896];
        snprintf(to_phone, sizeof(to_phone),
                 "{\"type\":\"session_owner\",\"remainMs\":%llu,\"controlKey\":\"%s\","
                 "\"desktopId\":\"%s\",\"name\":\"%s\",\"detail\":\"%s\",\"device\":\"%s\"}",
                 static_cast<unsigned long long>(remain_ms),
                 control_key.c_str(),
                 json_escape(desktop_id).c_str(),
                 json_escape(name).c_str(),
                 json_escape(detail).c_str(),
                 json_escape(device).c_str());
        send_to_agent_json(agent_id, to_phone);
    }

    /**
     * The phone registered again under a share that already has a holder. Give
     * that socket the key. If the holder is no longer connected, drop the claim
     * so the phone is not stuck listed and uncontrollable.
     */
    void push_existing_owner(const std::string &agent_id)
    {
        std::string owner;
        std::string key;
        uint64_t remain = 0;
        {
            std::lock_guard<std::mutex> lock(claim_mu_);
            auto it = sessions_.find(agent_id);
            if (it == sessions_.end() || it->second.consumed || it->second.owner.empty()
                    || it->second.control_key.empty()) {
                return;
            }
            owner = it->second.owner;
            key = it->second.control_key;
            const uint64_t now = now_ms();
            remain = it->second.deadline_ms > now ? it->second.deadline_ms - now : 0;
        }
        std::string name;
        std::string detail;
        std::string device;
        bool found = false;
        {
            std::lock_guard<std::mutex> lock(tcp_mutex_);
            for (DesktopSlot *desktop : desktops_) {
                if (desktop->id == owner) {
                    name = desktop->name;
                    detail = desktop->detail;
                    device = desktop->device;
                    found = true;
                    break;
                }
            }
        }
        if (!found) {
            free_claim(owner, agent_id, "owner offline");
            return;
        }
        send_session_owner(agent_id, owner, name, detail, device, key, remain);
    }

    void send_to_agent_json(const std::string &agent_id, const std::string &json)
    {
        auto agent = find_agent(agent_id);
        if (!agent) {
            return;
        }
        std::lock_guard<std::mutex> io(agent->io_mu);
        if (agent->alive.load() && agent->sock != INVALID_SOCKET) {
            send_control(agent->sock, json.c_str());
        }
    }

    void send_desktop_json(DesktopSlot *desktop, const std::string &json)
    {
        if (!desktop || desktop->sock == INVALID_SOCKET) {
            return;
        }
        std::lock_guard<std::mutex> lock(desktop->send_mu);
        if (desktop->sock != INVALID_SOCKET) {
            send_control(desktop->sock, json.c_str());
        }
    }

    /** True while this object is the phone socket the map currently points at. */
    bool is_current_agent(const std::shared_ptr<AgentSlot> &agent)
    {
        if (!agent) {
            return false;
        }
        std::lock_guard<std::mutex> lock(tcp_mutex_);
        auto it = agents_.find(agent->id);
        return it != agents_.end() && it->second == agent;
    }

    /**
     * Tell every controller the same thing about this mobile, from the relay's
     * current record. A delayed goodbye from an older socket calls this too, so
     * it republishes the phone that replaced it instead of wiping the lists.
     */
    void publish_current(const std::string &agent_id)
    {
        if (agent_id.empty()) {
            return;
        }
        std::lock_guard<std::mutex> announce(announce_mu_);
        std::shared_ptr<AgentSlot> agent = find_agent(agent_id);
        std::string owner;
        bool consumed = false;
        {
            std::lock_guard<std::mutex> lock(claim_mu_);
            auto it = sessions_.find(agent_id);
            if (it != sessions_.end()) {
                owner = it->second.owner;
                consumed = it->second.consumed;
            }
        }
        std::vector<DesktopSlot *> desktops;
        {
            std::lock_guard<std::mutex> lock(tcp_mutex_);
            desktops = desktops_;
        }
        const bool live = agent && agent->alive.load();
        const bool ended_agent = agent && agent->ended.load();
        const uint32_t session_id = agent ? agent->session_id : 0;
        // A stopped phone stays in the map until its socket closes. With the
        // session already erased it used to look unowned and ready, so every
        // controller put the row back and the preview window opened again.
        if (!live || consumed || ended_agent || !agent->ready.load()) {
            for (DesktopSlot *desktop : desktops) {
                notify_offline(desktop, agent_id, false, consumed || ended_agent, session_id);
            }
            return;
        }
        for (DesktopSlot *desktop : desktops) {
            if (!owner.empty() && desktop->id != owner) {
                notify_offline(desktop, agent_id, true, false, session_id);
            } else {
                notify_desktop(desktop, agent.get(), true);
            }
        }
    }

    /**
     * A controller asks to take this mobile. Granted only while the offer is still
     * open and unowned; the same controller asking again (its relay socket
     * reconnected) keeps what it already holds without restarting the hour.
     */
    void request_claim(DesktopSlot *desktop, const std::string &agent_id)
    {
        if (!desktop || desktop->id.empty() || agent_id.empty()) {
            return;
        }
        bool granted = false;
        bool became_busy = false;
        uint64_t remain = 0;
        std::string control_key;
        const char *reason = "in_use";
        // A mobile that is gone, or whose capture or accessibility is down, cannot
        // serve anybody. Refusing here is what keeps a second operator from taking
        // a session that would open onto a black window.
        {
            auto agent = find_agent(agent_id);
            if (!agent || !agent->alive.load() || !agent->ready.load() || agent->ended.load()) {
                char json[256];
                snprintf(json, sizeof(json),
                         "{\"type\":\"claim_denied\",\"agentId\":\"%s\","
                         "\"reason\":\"not_ready\"}",
                         json_escape(agent_id).c_str());
                send_desktop_json(desktop, json);
                return;
            }
        }
        {
            std::lock_guard<std::mutex> lock(claim_mu_);
            auto found = sessions_.find(agent_id);
            // Stop erases the offer. Creating one here used to grant a brand
            // new session to the window that was trying to re-attach.
            if (found == sessions_.end()) {
                reason = "session_closed";
            } else {
            ShareSession &s = found->second;
            const uint64_t now = now_ms();
            if (s.consumed || (s.deadline_ms != 0 && now >= s.deadline_ms)) {
                reason = "session_closed";
            } else if (!s.owner.empty() && s.owner != desktop->id) {
                reason = "in_use";
            } else {
                if (s.owner.empty()) {
                    s.owner = desktop->id;
                    // The hour starts at the first accept and then belongs to the
                    // mobile's sharing session. A controller taking over from one
                    // that closed its window inherits what is left of it; only the
                    // mobile sharing again buys a new hour.
                    if (s.deadline_ms == 0) {
                        s.deadline_ms = now + kSessionLimitMs;
                    }
                    // A new holder gets a new key, which is what stops the previous
                    // one's input the instant this one is granted.
                    s.control_key = make_control_key();
                    became_busy = true;
                }
                granted = true;
                control_key = s.control_key;
                remain = s.deadline_ms > now ? s.deadline_ms - now : 0;
            }
            }
        }
        char json[256];
        if (!granted) {
            snprintf(json, sizeof(json),
                     "{\"type\":\"claim_denied\",\"agentId\":\"%s\",\"reason\":\"%s\"}",
                     json_escape(agent_id).c_str(), reason);
            send_desktop_json(desktop, json);
            return;
        }
        snprintf(json, sizeof(json),
                 "{\"type\":\"claim_ok\",\"agentId\":\"%s\",\"remainMs\":%llu,"
                 "\"controlKey\":\"%s\"}",
                 json_escape(agent_id).c_str(), static_cast<unsigned long long>(remain),
                 control_key.c_str());
        send_desktop_json(desktop, json);
        // Always, including a re-claim of a session this desktop already holds.
        // After a dropped link the phone's new socket has no key until this
        // arrives, so input is refused even though the row is back on the list.
        send_session_owner(agent_id, desktop->id, desktop->name, desktop->detail,
                           desktop->device, control_key, remain);
        if (!became_busy) {
            return;
        }
        publish_current(agent_id);
    }

    /**
     * The offer is spent (owner lost without releasing, or the hour elapsed). Nobody
     * may take over: every table drops the row and the phone is told to stop
     * sharing, so the only way back is the user starting to share again.
     */
    void end_share_session(const std::string &agent_id, const char *why)
    {
        bool first = false;
        {
            std::lock_guard<std::mutex> lock(claim_mu_);
            auto it = sessions_.find(agent_id);
            if (it == sessions_.end()) {
                return;
            }
            if (!it->second.consumed) {
                first = true;
            }
            it->second.consumed = true;
            it->second.owner.clear();
            it->second.control_key.clear();
            it->second.deadline_ms = 0;
        }
        if (!first) {
            return;
        }
        printf("[relay] share session over for %s (%s)\n", agent_id.c_str(), why);
        fflush(stdout);
        // The phone's goodbye must remove the row, not put the device back.
        if (auto live = find_agent(agent_id)) {
            live->ended.store(true);
        }
        // Drop the preview socket now. Waiting for the controller to notice the
        // offline row left it looking connected after the hour had already ended.
        end_viewers(agent_id);
        send_to_agent_json(agent_id, "{\"type\":\"share_end\"}");
        publish_current(agent_id);
    }

    /** Ends any session whose hour has run out. Driven by the receive thread. */
    void expire_sessions()
    {
        std::vector<std::string> expired;
        {
            std::lock_guard<std::mutex> lock(claim_mu_);
            const uint64_t now = now_ms();
            for (auto &kv : sessions_) {
                // An unowned session still runs down: the hour is the mobile's, so
                // a controller leaving in the middle does not pause it.
                if (!kv.second.consumed && kv.second.deadline_ms != 0
                        && now >= kv.second.deadline_ms) {
                    expired.push_back(kv.first);
                }
            }
        }
        for (const std::string &agent_id : expired) {
            end_share_session(agent_id, "one hour elapsed");
        }
    }

    std::shared_ptr<AgentSlot> find_agent(const std::string &agent_id)
    {
        std::lock_guard<std::mutex> lock(tcp_mutex_);
        auto it = agents_.find(agent_id);
        return it == agents_.end() ? nullptr : it->second;
    }

    void for_other_desktops(const std::string &except_desktop_id,
                            const std::function<void(DesktopSlot *)> &fn)
    {
        std::vector<DesktopSlot *> desktops;
        {
            std::lock_guard<std::mutex> lock(tcp_mutex_);
            desktops = desktops_;
        }
        for (DesktopSlot *d : desktops) {
            if (!except_desktop_id.empty() && d->id == except_desktop_id) {
                continue;
            }
            fn(d);
        }
    }

    /**
     * The phone renamed itself. The list already shows this agent, so republish
     * the same session with the new label instead of dropping the share.
     */
    void rename_agent(const std::shared_ptr<AgentSlot> &agent, const std::vector<uint8_t> &packet)
    {
        if (!is_current_agent(agent)) {
            return;
        }
        std::string device;
        packet_json_field(packet, "device", &device);
        // The phone already keeps this to 48 characters. Cap the bytes so a
        // long UTF-8 name still fits the desktop notify buffer, and stop on a
        // character boundary so the last glyph is not split.
        if (device.size() > 192) {
            device.resize(192);
            while (!device.empty() &&
                   (static_cast<unsigned char>(device.back()) & 0xC0) == 0x80) {
                device.pop_back();
            }
        }
        while (!device.empty() && (device.back() == ' ' || device.back() == '\t')) {
            device.pop_back();
        }
        if (device.empty() || device == agent->device) {
            return;
        }
        agent->device = std::move(device);
        printf("[relay] %s renamed to %s\n", agent->id.c_str(), agent->device.c_str());
        fflush(stdout);
        publish_current(agent->id);
    }

    /**
     * The phone's own report of whether it can serve a session: screen capture
     * granted and running, accessibility bound. Nothing is offered until this is
     * true, and a mobile that loses either mid-session comes straight back off the
     * list — a row an operator can click but that cannot stream or be clicked is
     * worse than no row at all.
     */
    void set_agent_ready(const std::shared_ptr<AgentSlot> &agent, bool ready)
    {
        // A packet still sitting on a socket that a newer registration replaced.
        // Applying it would take the live phone off every controller's list.
        if (!agent || !is_current_agent(agent) || agent->ended.load()) {
            return;
        }
        if (agent->ready.exchange(ready) == ready) {
            return;
        }
        if (!is_current_agent(agent)) {
            return;
        }
        printf("[relay] %s is %s\n", agent->id.c_str(),
               ready ? "ready to share" : "not ready (services down)");
        fflush(stdout);
        if (!ready) {
            // Whoever holds it loses it. The session cannot carry on without
            // capture or without input, and nobody else may pick it up either.
            const std::string owner = claimed_by_desktop(agent->id);
            if (!owner.empty()) {
                free_claim(owner, agent->id, "mobile services stopped");
            } else {
                publish_current(agent->id);
            }
            return;
        }
        if (session_consumed(agent->id)) {
            return;
        }
        publish_current(agent->id);
    }

    // Preview closed: put the phone back on every other table, or show it offline
    // if it left while the window was open.
    void publish_unclaimed(const std::string &except_desktop_id, const std::string &agent_id)
    {
        (void) except_desktop_id;
        // Every controller, including the one that just let go, gets the same
        // picture. Leaving one of them out is how their lists drifted apart.
        publish_current(agent_id);
    }

    /**
     * The controller let this mobile go — by closing its window, or by dying. Both
     * are handled the same way on purpose: the mobile stops streaming at once and
     * goes straight back on offer, so the next controller can take it without the
     * customer touching the phone.
     *
     * The hour is not reset. It belongs to the mobile's sharing session, not to
     * whoever happens to be holding it, so a handover continues the same hour.
     */
    void free_claim(const std::string &desktop_id, const std::string &agent_id,
                    const char *why, bool end_share = false)
    {
        if (desktop_id.empty() || agent_id.empty()) {
            return;
        }
        bool became_free = false;
        std::shared_ptr<AgentSlot> stopping = find_agent(agent_id);
        const uint32_t stopping_session = stopping ? stopping->session_id : 0;
        {
            std::lock_guard<std::mutex> lock(claim_mu_);
            auto it = sessions_.find(agent_id);
            if (it != sessions_.end() && it->second.owner == desktop_id) {
                // A Start that already replaced this socket owns the offer.
                // This stop must not erase that one.
                const bool same_socket = !stopping
                        || it->second.slot_session == stopping_session;
                if (end_share && same_socket) {
                    // Same end state as the handset Stop: no owner, no key, no
                    // hour left to resume. The next Start sends a new share id.
                    // Mark the socket ended before the session disappears, so a
                    // list refresh in between cannot publish it as available.
                    if (stopping) {
                        stopping->ended.store(true);
                    }
                    remember_stopped_locked(agent_id, it->second);
                    sessions_.erase(it);
                    became_free = true;
                } else if (!end_share && !it->second.consumed) {
                    it->second.owner.clear();
                    it->second.control_key.clear();
                    became_free = true;
                }
            }
        }
        if (!became_free) {
            return;
        }
        printf("[relay] %s released by %s (%s)\n", agent_id.c_str(),
               desktop_id.c_str(), why);
        fflush(stdout);
        // Drop the preview socket so the phone is told to stop capturing right
        // away. Without this the phone keeps streaming into a window nobody is
        // watching, and the next controller inherits that backlog. Stop also
        // tells the open window to close; a plain release only detaches it.
        if (end_share) {
            end_viewers(agent_id);
        } else {
            close_viewers_of(agent_id);
        }
        // Then say it outright. Closing the preview socket used to be the only
        // signal the phone got, which made the teardown depend on which thread
        // noticed the dead socket first — and on a controller that managed to
        // flush its own stop before its threads were torn down. The phone must
        // not be left controllable by a controller that is already gone, so the
        // relay states it on the one link that is certainly up.
        if (end_share) {
            // Same outcome as the handset's Stop button: sharing and remote
            // control end, and the app is left running. Mark only the socket
            // that was holding the offer, so a newer Start stays up.
            auto live = find_agent(agent_id);
            if (live && stopping && live.get() == stopping.get()) {
                live->ended.store(true);
                send_to_agent_json(agent_id, "{\"type\":\"share_stop\"}");
            }
            // Every controller drops the row and closes its window now. Waiting
            // for the phone socket to die left the preview re-attaching.
            publish_current(agent_id);
            return;
        }
        send_to_agent_json(agent_id,
                "{\"type\":\"command\",\"action\":\"screen_monitor_stop\",\"payload\":{}}");
        send_to_agent_json(agent_id, "{\"type\":\"control_revoked\"}");
        publish_unclaimed(desktop_id, agent_id);
    }

    void release_claim(DesktopSlot *desktop, const std::string &agent_id,
                       bool end_share = false)
    {
        if (!desktop) {
            return;
        }
        free_claim(desktop->id, agent_id, end_share ? "stop button" : "window closed",
                   end_share);
    }


    void release_claims_of(DesktopSlot *desktop)
    {
        if (!desktop || desktop->id.empty()) {
            return;
        }
        {
            // A second socket with the same id is a reconnect that still owns
            // these previews. Dropping the claims here would flash the phones
            // back onto every other table.
            std::lock_guard<std::mutex> lock(tcp_mutex_);
            for (DesktopSlot *other : desktops_) {
                if (other != desktop && other->id == desktop->id) {
                    return;
                }
            }
        }
        std::vector<std::string> agents;
        {
            std::lock_guard<std::mutex> lock(claim_mu_);
            for (auto &kv : sessions_) {
                if (kv.second.owner == desktop->id) {
                    agents.push_back(kv.first);
                }
            }
        }
        for (const std::string &agent_id : agents) {
            free_claim(desktop->id, agent_id, "controller disconnected");
        }
    }

    /**
     * Cut loose any control socket that has gone quiet. The desktop pings, so
     * silence means the controller is gone even though TCP has not noticed yet —
     * and until it is cut, the mobile it holds is stuck being unavailable.
     */
    void expire_desktops()
    {
        std::vector<SOCKET> dead;
        {
            const uint64_t now = now_ms();
            std::lock_guard<std::mutex> lock(tcp_mutex_);
            for (DesktopSlot *d : desktops_) {
                const uint64_t last = d->last_rx_ms.load();
                if (last != 0 && now > last + kDesktopIdleMs
                        && d->sock != INVALID_SOCKET) {
                    dead.push_back(d->sock);
                }
            }
        }
        // shutdown() unblocks the reader, whose own thread then runs
        // release_claims_of and hands the mobile back.
        for (SOCKET s : dead) {
            shutdown(s, SD_BOTH);
        }
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
