#include "screen_layout.h"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <filesystem>
#include <mutex>
#include <string>

#include "host.h"

namespace layout {
namespace {
#ifdef __ANDROID__
// the TV picture first; the GamePad-only view (with the game's Off-TV Play, Minus) is a tap away
std::atomic<int> g_view{kTv};
std::atomic<bool> g_fps60{true};
#else
std::atomic<int> g_view{kTvWithGamePad};
std::atomic<bool> g_fps60{false};
#endif
std::string settings_path() { return host::config_dir() + "/android.txt"; }
void save_settings() {
#ifdef __ANDROID__
    std::error_code ec;
    std::filesystem::create_directories(host::config_dir(), ec);
    if (FILE* f = fopen(settings_path().c_str(), "w")) {
        fprintf(f, "view=%d\nfps60=%d\n", g_view.load(), g_fps60.load() ? 1 : 0);
        fclose(f);
    }
#endif
}
std::mutex g_mu;
Frame g_tv_frame;    // last single-screen frame of the TV window
Rect g_drc_picture;  // last GamePad picture in the GamePad window (two-window mode)
}

bool single_screen() {
#ifdef __ANDROID__
    static const bool on = true;
#else
    static const bool on = [] { const char* e = getenv("WWHD_SINGLE_SCREEN"); return e && atoi(e) != 0; }();
#endif
    return on;
}
View view() { return (View)g_view.load(); }
void next_view() {
    g_view.store((g_view.load() + 1) % kViewCount);
    save_settings();
}

bool load_settings() {
#ifdef __ANDROID__
    if (FILE* f = fopen(settings_path().c_str(), "r")) {
        int v = 0;
        char line[64];
        while (fgets(line, sizeof line, f)) {
            if (sscanf(line, "view=%d", &v) == 1 && v >= 0 && v < kViewCount) g_view = v;
            if (sscanf(line, "fps60=%d", &v) == 1) g_fps60 = v != 0;
        }
        fclose(f);
    }
#endif
    return g_fps60.load();
}
bool fps60() { return g_fps60.load(); }
void set_fps60(bool on) {
    g_fps60 = on;
    save_settings();
}

Rect fit(float x, float y, float w, float h, float src_w, float src_h) {
    if (w <= 0 || h <= 0 || src_w <= 0 || src_h <= 0) return {};
    float s = std::min(w / src_w, h / src_h);
    float fw = src_w * s, fh = src_h * s;
    return {x + (w - fw) / 2, y + (h - fh) / 2, fw, fh};
}

Frame compute(View v, float w, float h, float tv_w, float tv_h, float drc_w, float drc_h, bool toggle_button) {
    Frame f;
    const float m = std::round(std::min(w, h) * 0.015f);  // margin of the small picture
    bool tv = tv_w > 0 && tv_h > 0, drc = drc_w > 0 && drc_h > 0;
    // the small picture: a corner, 30% of the width (at most 40% of the height)
    auto small = [&](float sw, float sh, bool top) {
        float bw = std::min(w * 0.30f, h * 0.40f * sw / sh), bh = bw * sh / sw;
        return Rect{w - m - bw, top ? m : h - m - bh, bw, bh};
    };
    switch (v) {
    case kTvWithGamePad:
        if (tv) f.tv = fit(0, 0, w, h, tv_w, tv_h);
        if (drc) f.drc = small(drc_w, drc_h, false);
        break;
    case kGamePad:  // only the GamePad, like a Wii U without a TV
        if (drc) f.drc = fit(0, 0, w, h, drc_w, drc_h);
        break;
    default:
        if (tv) f.tv = fit(0, 0, w, h, tv_w, tv_h);
        break;
    }
    if (!drc && !tv) f.drc_on_top = false;
    if (toggle_button) {
        float s = std::round(std::min(w, h) * 0.09f);
        f.toggle = {m, m, s, s};
    }
    return f;
}

Frame present_tv(float w, float h, float tv_w, float tv_h, float drc_w, float drc_h) {
#ifdef __ANDROID__
    const bool button = true;
#else
    const bool button = false;  // keyboard: G
#endif
    Frame f = compute(view(), w, h, tv_w, tv_h, drc_w, drc_h, button);
    std::lock_guard<std::mutex> lk(g_mu);
    g_tv_frame = f;
    return f;
}

void present_drc_window(const Rect& picture) {
    std::lock_guard<std::mutex> lk(g_mu);
    g_drc_picture = picture;
}

Hit hit(bool tv_window, float x, float y, float& tx, float& ty) {
    std::lock_guard<std::mutex> lk(g_mu);
    Rect drc = tv_window ? (single_screen() ? g_tv_frame.drc : Rect{}) : g_drc_picture;
    if (tv_window && single_screen() && g_tv_frame.toggle.contains(x, y)) return Hit::Toggle;
    if (drc.empty()) return Hit::None;
    tx = std::clamp((x - drc.x) / drc.w, 0.0f, 1.0f);
    ty = std::clamp((y - drc.y) / drc.h, 0.0f, 1.0f);
    if (!drc.contains(x, y)) return Hit::None;
    // the GamePad picture under the TV picture (GamePad view): the TV corner is not a touch
    if (tv_window && !g_tv_frame.drc_on_top && g_tv_frame.tv.contains(x, y)) return Hit::None;
    return Hit::GamePad;
}

}  // namespace layout
