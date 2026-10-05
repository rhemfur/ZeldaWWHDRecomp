// Single-screen layout (SDL host): the TV and GamePad pictures share one window (Android, where an
// app has a single surface, or WWHD_SINGLE_SCREEN=1 on a desktop), and where the GamePad picture is
// on screen so that touches and clicks on it reach the game as GamePad touch-screen input.
#pragma once

namespace layout {

struct Rect {
    float x = 0, y = 0, w = 0, h = 0;
    bool empty() const { return w <= 0 || h <= 0; }
    bool contains(float px, float py) const { return !empty() && px >= x && py >= y && px < x + w && py < y + h; }
};

// what fills the window in single-screen mode; G (keyboard) or the on-screen button cycles them.
// kGamePad shows only the GamePad, like a Wii U played without a TV (the default on Android): in
// Wind Waker HD, Minus switches the game to Off-TV Play, which puts the game itself on the GamePad.
enum View { kTvWithGamePad, kGamePad, kTv, kViewCount };

bool single_screen();
View view();
void next_view();

// Android: the view and the 60 fps choice (frame interpolation) are kept in <config>/android.txt.
// load_settings() runs at start-up and says whether 60 fps was chosen; fps60()/set_fps60() track the
// choice made with a long press on the view button. platform/perf_hint.cpp turns interpolation on
// and off for it where the phone can or cannot draw 60 frames a second.
bool load_settings();
bool fps60();
void set_fps60(bool on);

// one frame of the single-screen window, in its pixels
struct Frame {
    Rect tv, drc;         // empty when not shown
    bool drc_on_top = true;  // the small picture is drawn over the large one
    Rect toggle;          // the on-screen view button (touch screens)
};

// a picture of src_w x src_h fitted into a w x h area at (x, y), centred, aspect kept
Rect fit(float x, float y, float w, float h, float src_w, float src_h);

// render thread: the layout for a window of w x h pixels with the TV and GamePad pictures of
// these sizes (0 when the game has not shown one yet); remembered for touch_point()
Frame compute(View v, float w, float h, float tv_w, float tv_h, float drc_w, float drc_h, bool toggle_button);
Frame present_tv(float w, float h, float tv_w, float tv_h, float drc_w, float drc_h);
// render thread, two-window mode: where the GamePad picture is in the GamePad window
void present_drc_window(const Rect& picture);

enum class Hit { None, GamePad, Toggle };
// main thread: a point in window pixels. GamePad: (tx, ty) is the touch position 0..1 from the top
// left of the GamePad picture (clamped, so a drag that leaves the picture keeps touching its edge)
Hit hit(bool tv_window, float x, float y, float& tx, float& ty);

}  // namespace layout
