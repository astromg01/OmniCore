#pragma once

#include "../libretro_abi.h"

#include <cstdint>
#include <climits>

namespace omnicore::n64::abi {

using retro_proc_address_t = void (*)();
using retro_hw_context_reset_t = void (*)();
using retro_hw_get_current_framebuffer_t = std::uintptr_t (*)();
using retro_hw_get_proc_address_t = retro_proc_address_t (*)(const char* sym);

enum retro_hw_context_type {
    RETRO_HW_CONTEXT_NONE = 0,
    RETRO_HW_CONTEXT_OPENGL = 1,
    RETRO_HW_CONTEXT_OPENGLES2 = 2,
    RETRO_HW_CONTEXT_OPENGL_CORE = 3,
    RETRO_HW_CONTEXT_OPENGLES3 = 4,
    RETRO_HW_CONTEXT_OPENGLES_VERSION = 5,
    RETRO_HW_CONTEXT_VULKAN = 6,
    RETRO_HW_CONTEXT_DIRECT3D = 7,
    RETRO_HW_CONTEXT_DUMMY = INT_MAX
};

// Stable libretro hardware-render callback layout. This lives in the N64
// backend so the PS1 software-frame presenter never depends on GLES state.
struct retro_hw_render_callback {
    retro_hw_context_type context_type;
    retro_hw_context_reset_t context_reset;
    retro_hw_get_current_framebuffer_t get_current_framebuffer;
    retro_hw_get_proc_address_t get_proc_address;
    bool depth;
    bool stencil;
    bool bottom_left_origin;
    unsigned version_major;
    unsigned version_minor;
    bool cache_context;
    retro_hw_context_reset_t context_destroy;
    bool debug_context;
};

constexpr unsigned RETRO_ENVIRONMENT_SET_HW_RENDER = 14;
constexpr unsigned RETRO_ENVIRONMENT_GET_RUMBLE_INTERFACE = 23;
constexpr unsigned RETRO_ENVIRONMENT_GET_PERF_INTERFACE = 28;
constexpr unsigned RETRO_ENVIRONMENT_RETROARCH_START_BLOCK = 0x800000u;
constexpr unsigned RETRO_ENVIRONMENT_GET_CLEAR_ALL_THREAD_WAITS_CB =
    3u | RETRO_ENVIRONMENT_RETROARCH_START_BLOCK;
constexpr unsigned RETRO_ENVIRONMENT_POLL_TYPE_OVERRIDE =
    4u | RETRO_ENVIRONMENT_RETROARCH_START_BLOCK;

inline const void* const RETRO_HW_FRAME_BUFFER_VALID =
    reinterpret_cast<const void*>(static_cast<std::intptr_t>(-1));

}  // namespace omnicore::n64::abi
