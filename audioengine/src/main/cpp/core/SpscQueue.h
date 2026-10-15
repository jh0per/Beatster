#pragma once

#include <array>
#include <atomic>
#include <cstddef>

namespace beatster {

/**
 * Lock-free single-producer / single-consumer ring buffer.
 *
 * The control side (UI/JNI, serialised by a mutex in the bridge) pushes and the audio
 * callback pops, or the other way round for the event queue. Neither side ever blocks
 * or allocates, so it is safe to use from the real-time audio thread.
 */
template <typename T, size_t Capacity>
class SpscQueue {
    static_assert((Capacity & (Capacity - 1)) == 0, "Capacity must be a power of two");

public:
    bool push(const T& item) {
        const size_t head = mHead.load(std::memory_order_relaxed);
        const size_t tail = mTail.load(std::memory_order_acquire);
        if (head - tail >= Capacity) return false;
        mItems[head & (Capacity - 1)] = item;
        mHead.store(head + 1, std::memory_order_release);
        return true;
    }

    bool pop(T& out) {
        const size_t tail = mTail.load(std::memory_order_relaxed);
        const size_t head = mHead.load(std::memory_order_acquire);
        if (tail == head) return false;
        out = mItems[tail & (Capacity - 1)];
        mTail.store(tail + 1, std::memory_order_release);
        return true;
    }

private:
    std::array<T, Capacity> mItems{};
    std::atomic<size_t> mHead{0};
    std::atomic<size_t> mTail{0};
};

} // namespace beatster
