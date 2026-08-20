#include <jni.h>

#include <dirent.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <cctype>
#include <cstdint>
#include <cstdio>
#include <fstream>
#include <iomanip>
#include <mutex>
#include <optional>
#include <sstream>
#include <string>
#include <unordered_map>

namespace {

using Clock = std::chrono::steady_clock;

struct ThreadSnapshot {
    std::unordered_map<pid_t, std::uint64_t> ticks;
    Clock::time_point wall{};
    bool initialized = false;
    std::string cached;
    Clock::time_point cachedAt{};
};

ThreadSnapshot g_snapshot;
std::mutex g_snapshotMutex;

std::string lower(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char c) {
        return static_cast<char>(std::tolower(c));
    });
    return value;
}

std::optional<std::string> readThreadName(pid_t tid) {
    char path[96]{};
    std::snprintf(path, sizeof(path), "/proc/self/task/%d/comm", static_cast<int>(tid));
    std::ifstream in(path);
    if (!in) return std::nullopt;
    std::string name;
    std::getline(in, name);
    if (!name.empty() && name.back() == '\r') name.pop_back();
    return name;
}

std::optional<std::uint64_t> readThreadTicks(pid_t tid) {
    char path[96]{};
    std::snprintf(path, sizeof(path), "/proc/self/task/%d/stat", static_cast<int>(tid));
    std::ifstream in(path);
    if (!in) return std::nullopt;

    std::string line;
    std::getline(in, line);
    const std::size_t close = line.rfind(')');
    if (close == std::string::npos || close + 2 >= line.size()) return std::nullopt;

    // After comm's closing ')' the whitespace-separated fields begin at field 3
    // (state). utime/stime are fields 14/15, therefore tokens 11/12 here.
    std::istringstream fields(line.substr(close + 2));
    std::string token;
    std::uint64_t utime = 0;
    std::uint64_t stime = 0;
    for (int index = 0; fields >> token; ++index) {
        try {
            if (index == 11) utime = static_cast<std::uint64_t>(std::stoull(token));
            if (index == 12) {
                stime = static_cast<std::uint64_t>(std::stoull(token));
                break;
            }
        } catch (...) {
            return std::nullopt;
        }
    }
    return utime + stime;
}

struct ThreadSet {
    pid_t ee = -1;
    pid_t vu = -1;
    pid_t gs = -1;
    pid_t gsBack = -1;
    std::unordered_map<pid_t, std::uint64_t> ticks;
};

ThreadSet collectThreads(pid_t requestedEeTid) {
    ThreadSet result;
    result.ee = requestedEeTid > 0 ? requestedEeTid : -1;

    DIR* dir = opendir("/proc/self/task");
    if (!dir) return result;

    while (dirent* entry = readdir(dir)) {
        if (entry->d_name[0] == '.') continue;
        char* end = nullptr;
        const long parsed = std::strtol(entry->d_name, &end, 10);
        if (!end || *end != '\0' || parsed <= 0) continue;
        const pid_t tid = static_cast<pid_t>(parsed);

        const auto ticks = readThreadTicks(tid);
        if (!ticks) continue;
        result.ticks.emplace(tid, *ticks);

        const auto nameOpt = readThreadName(tid);
        if (!nameOpt) continue;
        const std::string name = lower(*nameOpt);

        // PCSX2 names these threads explicitly in the pinned core: "GS" and
        // "MTVU". OmniCore owns the EE/VM Java thread and passes its exact tid.
        if (name == "gs") {
            result.gs = tid;
        } else if (name == "mtvu") {
            result.vu = tid;
        } else if (name.find("gs") != std::string::npos &&
                   (name.find("back") != std::string::npos ||
                    name.find("parser") != std::string::npos)) {
            result.gsBack = tid;
        } else if (result.ee <= 0 &&
                   (name.find("omnicore-pcsx2") != std::string::npos ||
                    name.find("pcsx2-vm") != std::string::npos)) {
            result.ee = tid;
        }
    }

    closedir(dir);
    return result;
}

double usagePercent(
    pid_t tid,
    const std::unordered_map<pid_t, std::uint64_t>& current,
    const std::unordered_map<pid_t, std::uint64_t>& previous,
    double wallMs,
    long ticksPerSecond) {
    if (tid <= 0 || wallMs <= 0.0 || ticksPerSecond <= 0) return -1.0;
    const auto now = current.find(tid);
    const auto old = previous.find(tid);
    if (now == current.end() || old == previous.end() || now->second < old->second) return -1.0;

    const double cpuMs = static_cast<double>(now->second - old->second) * 1000.0 /
        static_cast<double>(ticksPerSecond);
    return std::clamp(cpuMs * 100.0 / wallMs, 0.0, 125.0);
}

double msPerFrame(double usage, float fps) {
    if (usage < 0.0 || fps <= 1.0f) return -1.0;
    return (usage / 100.0) * (1000.0 / static_cast<double>(fps));
}

std::string buildSnapshot(pid_t eeTid, float fps, float nominalFps) {
    std::lock_guard<std::mutex> lock(g_snapshotMutex);
    const auto now = Clock::now();

    // The HUD and the background learner can ask for a sample close together.
    // Reuse the last completed interval rather than turning a sub-100 ms delta
    // into noisy 0/100% readings.
    if (!g_snapshot.cached.empty() &&
        std::chrono::duration_cast<std::chrono::milliseconds>(now - g_snapshot.cachedAt).count() < 180) {
        return g_snapshot.cached;
    }

    ThreadSet threads = collectThreads(eeTid);
    if (threads.ticks.empty()) {
        return "ok=0;source=android-procfs-no-threads";
    }

    if (!g_snapshot.initialized) {
        g_snapshot.ticks = std::move(threads.ticks);
        g_snapshot.wall = now;
        g_snapshot.initialized = true;
        return "ok=0;source=android-procfs-warming";
    }

    const double wallMs = std::chrono::duration<double, std::milli>(now - g_snapshot.wall).count();
    if (wallMs < 80.0) {
        return g_snapshot.cached.empty() ? "ok=0;source=android-procfs-warming" : g_snapshot.cached;
    }

    const long ticksPerSecond = sysconf(_SC_CLK_TCK);
    const double eePct = usagePercent(threads.ee, threads.ticks, g_snapshot.ticks, wallMs, ticksPerSecond);
    const double vuPct = usagePercent(threads.vu, threads.ticks, g_snapshot.ticks, wallMs, ticksPerSecond);
    const double gsPct = usagePercent(threads.gs, threads.ticks, g_snapshot.ticks, wallMs, ticksPerSecond);
    const double gsbPct = usagePercent(threads.gsBack, threads.ticks, g_snapshot.ticks, wallMs, ticksPerSecond);

    g_snapshot.ticks = std::move(threads.ticks);
    g_snapshot.wall = now;

    const bool available = eePct >= 0.0 || vuPct >= 0.0 || gsPct >= 0.0 || gsbPct >= 0.0;
    if (!available) {
        g_snapshot.cached = "ok=0;source=android-procfs-thread-match-missing";
        g_snapshot.cachedAt = now;
        return g_snapshot.cached;
    }

    const double frameAvgMs = fps > 1.0f ? (1000.0 / static_cast<double>(fps)) : -1.0;
    const double speedPct = (fps > 1.0f && nominalFps > 1.0f)
        ? std::clamp(static_cast<double>(fps) * 100.0 / static_cast<double>(nominalFps), 0.0, 300.0)
        : -1.0;

    std::ostringstream out;
    out.setf(std::ios::fixed);
    out << std::setprecision(3)
        << "ok=1;source=android-procfs-thread-times"
        << ";speedPct=" << speedPct
        << ";internalFps=-1"
        << ";eePct=" << eePct
        << ";eeMs=" << msPerFrame(eePct, fps)
        << ";vuPct=" << vuPct
        << ";vuMs=" << msPerFrame(vuPct, fps)
        << ";gsPct=" << gsPct
        << ";gsMs=" << msPerFrame(gsPct, fps)
        << ";gsbPct=" << gsbPct
        << ";gsbMs=" << msPerFrame(gsbPct, fps)
        << ";gpuPct=-1;gpuMs=-1"
        << ";frameAvgMs=" << frameAvgMs
        << ";frameMinMs=-1;frameMaxMs=-1"
        << ";vs=-1;ps=-1"
        << ";eeTid=" << static_cast<int>(threads.ee)
        << ";vuTid=" << static_cast<int>(threads.vu)
        << ";gsTid=" << static_cast<int>(threads.gs)
        << ";gsbTid=" << static_cast<int>(threads.gsBack);

    g_snapshot.cached = out.str();
    g_snapshot.cachedAt = now;
    return g_snapshot.cached;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_omnicore_emulator_core_ps2_PS2NativeBridge_nativePcsx2ThreadPerformance(
    JNIEnv* env,
    jobject /* thiz */,
    jint eeTid,
    jfloat fps,
    jfloat nominalFps) {
    const std::string snapshot = buildSnapshot(static_cast<pid_t>(eeTid), fps, nominalFps);
    return env->NewStringUTF(snapshot.c_str());
}
