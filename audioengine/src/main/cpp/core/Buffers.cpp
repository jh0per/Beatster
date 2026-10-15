#include "Buffers.h"

#include <algorithm>
#include <cmath>

namespace beatster {

std::unique_ptr<Sample> createSample(const float* interleaved, int64_t frames, int32_t channels,
                                     int32_t sourceRate, int32_t targetRate) {
    if (interleaved == nullptr || frames <= 0 || channels <= 0 || sourceRate <= 0 || targetRate <= 0) {
        return nullptr;
    }
    const int32_t outChannels = channels >= 2 ? 2 : 1;

    // Fold the source down to 1 or 2 channels first.
    std::vector<float> folded(static_cast<size_t>(frames * outChannels));
    for (int64_t f = 0; f < frames; ++f) {
        const float* src = interleaved + f * channels;
        if (outChannels == 1) {
            folded[f] = src[0];
        } else if (channels == 2) {
            folded[f * 2] = src[0];
            folded[f * 2 + 1] = src[1];
        } else {
            // Even channels to the left, odd to the right.
            float left = 0.0f, right = 0.0f;
            int32_t nl = 0, nr = 0;
            for (int32_t c = 0; c < channels; ++c) {
                if (c % 2 == 0) { left += src[c]; ++nl; } else { right += src[c]; ++nr; }
            }
            folded[f * 2] = left / static_cast<float>(std::max(nl, 1));
            folded[f * 2 + 1] = right / static_cast<float>(std::max(nr, 1));
        }
    }

    auto sample = std::make_unique<Sample>();
    sample->channels = outChannels;
    if (sourceRate == targetRate) {
        sample->frames = frames;
        sample->data = std::move(folded);
        return sample;
    }

    const double ratio = static_cast<double>(sourceRate) / static_cast<double>(targetRate);
    const auto outFrames = static_cast<int64_t>(std::floor(static_cast<double>(frames - 1) / ratio)) + 1;
    sample->frames = outFrames;
    sample->data.resize(static_cast<size_t>(outFrames * outChannels));
    for (int64_t f = 0; f < outFrames; ++f) {
        const double srcPos = static_cast<double>(f) * ratio;
        const auto i0 = static_cast<int64_t>(srcPos);
        const int64_t i1 = std::min(i0 + 1, frames - 1);
        const auto frac = static_cast<float>(srcPos - static_cast<double>(i0));
        for (int32_t c = 0; c < outChannels; ++c) {
            const float a = folded[static_cast<size_t>(i0 * outChannels + c)];
            const float b = folded[static_cast<size_t>(i1 * outChannels + c)];
            sample->data[static_cast<size_t>(f * outChannels + c)] = a + (b - a) * frac;
        }
    }
    return sample;
}

} // namespace beatster
