// Host-side tests for the platform-independent audio core (no Android, no Oboe).
// Build and run: cmake -S audioengine/src/test/cpp -B build/enginetests && cmake --build build/enginetests && build/enginetests/engine_tests

#include <atomic>
#include <cmath>
#include <cstdio>
#include <functional>
#include <string>
#include <thread>
#include <vector>

#include "Buffers.h"
#include "Engine.h"
#include "EngineController.h"

using namespace beatster;

namespace {

int gFailures = 0;
int gChecks = 0;

#define CHECK(cond)                                                                   \
    do {                                                                              \
        ++gChecks;                                                                    \
        if (!(cond)) {                                                                \
            ++gFailures;                                                              \
            std::printf("  FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond);             \
        }                                                                             \
    } while (0)

#define CHECK_NEAR(a, b, tol)                                                                       \
    do {                                                                                            \
        ++gChecks;                                                                                  \
        const double va = (a), vb = (b);                                                            \
        if (std::fabs(va - vb) > (tol)) {                                                           \
            ++gFailures;                                                                            \
            std::printf("  FAIL %s:%d: %s = %f, expected %f +- %f\n", __FILE__, __LINE__, #a, va, vb, \
                        static_cast<double>(tol));                                                  \
        }                                                                                           \
    } while (0)

constexpr int32_t kRate = 48000;
constexpr double kFramesPerBeat120 = kRate * 0.5;  // 120 bpm

struct Rendered {
    std::vector<float> left;
    std::vector<float> right;
};

/** Renders `frames` frames in awkward block sizes to exercise block-edge handling. */
Rendered renderFrames(Engine& engine, int64_t frames, const std::function<float(int64_t)>& input = nullptr,
                      int64_t* inputClock = nullptr) {
    Rendered r;
    r.left.reserve(static_cast<size_t>(frames));
    r.right.reserve(static_cast<size_t>(frames));
    static const int32_t kSizes[] = {97, 192, 256, 1, 480, 33, 1024};
    std::vector<float> out;
    std::vector<float> in;
    int64_t done = 0;
    size_t which = 0;
    while (done < frames) {
        const auto n = static_cast<int32_t>(std::min<int64_t>(kSizes[which++ % 7], frames - done));
        out.assign(static_cast<size_t>(n) * 2, 0.0f);
        const float* inPtr = nullptr;
        if (input) {
            in.resize(static_cast<size_t>(n));
            for (int32_t i = 0; i < n; ++i) in[static_cast<size_t>(i)] = input((inputClock ? *inputClock : done) + i);
            inPtr = in.data();
        }
        engine.render(out.data(), inPtr, n);
        for (int32_t i = 0; i < n; ++i) {
            r.left.push_back(out[static_cast<size_t>(i) * 2]);
            r.right.push_back(out[static_cast<size_t>(i) * 2 + 1]);
        }
        done += n;
        if (inputClock) *inputClock += n;
    }
    return r;
}

/** Frame indices where the signal goes from silent (for `gap` frames) to non-silent. */
std::vector<int64_t> onsets(const std::vector<float>& signal, float threshold = 1e-4f, int64_t gap = 200) {
    std::vector<int64_t> result;
    int64_t silentRun = gap;
    for (size_t i = 0; i < signal.size(); ++i) {
        if (std::fabs(signal[i]) > threshold) {
            if (silentRun >= gap) result.push_back(static_cast<int64_t>(i));
            silentRun = 0;
        } else {
            ++silentRun;
        }
    }
    return result;
}

double energy(const std::vector<float>& s, size_t from, size_t to) {
    double e = 0.0;
    for (size_t i = from; i < to && i < s.size(); ++i) e += static_cast<double>(s[i]) * s[i];
    return e;
}

/** A short burst of DC, so onsets are unambiguous. */
std::vector<float> burst(int frames, float value = 0.5f) { return std::vector<float>(static_cast<size_t>(frames), value); }

void loadBurst(EngineController& c, int pad, int frames = 400) {
    const auto b = burst(frames);
    c.setPadSample(pad, b.data(), static_cast<int64_t>(b.size()), 1, kRate);
}

void pump(EngineController& c, int blocks = 1) {
    std::vector<float> out(512);
    for (int i = 0; i < blocks; ++i) c.engine().render(out.data(), nullptr, 256);
}

void test(const char* name, const std::function<void()>& body) {
    const int before = gFailures;
    body();
    std::printf("%s %s\n", gFailures == before ? "PASS" : "FAIL", name);
}

} // namespace

int main() {
    test("createSample resamples 44.1k to 48k and folds channels", [] {
        std::vector<float> ramp(441);
        for (size_t i = 0; i < ramp.size(); ++i) ramp[i] = static_cast<float>(i) / 441.0f;
        auto s = createSample(ramp.data(), 441, 1, 44100, 48000);
        CHECK(s != nullptr);
        CHECK_NEAR(static_cast<double>(s->frames), 480.0, 1.0);
        CHECK_NEAR(s->at(240, 0), 220.5 / 441.0, 0.003);

        std::vector<float> quad = {1, 0, 1, 0, 1, 0, 1, 0};  // 2 frames, 4 channels
        auto q = createSample(quad.data(), 2, 4, 48000, 48000);
        CHECK(q->channels == 2);
        CHECK_NEAR(q->at(0, 0), 1.0, 1e-6);
        CHECK_NEAR(q->at(0, 1), 0.0, 1e-6);

        CHECK(createSample(nullptr, 10, 1, 48000, 48000) == nullptr);
        CHECK(createSample(ramp.data(), 0, 1, 48000, 48000) == nullptr);
    });

    test("tapping a pad plays its sample immediately", [] {
        EngineController c(kRate);
        auto silent = renderFrames(c.engine(), 1000);
        CHECK(energy(silent.left, 0, 1000) == 0.0);
        loadBurst(c, 3);
        c.noteOn(0, 3, 1.0f);
        auto r = renderFrames(c.engine(), 2000);
        const auto on = onsets(r.left);
        CHECK(on.size() == 1);
        CHECK(!on.empty() && on[0] < 5);
        CHECK(energy(r.left, 500, 2000) == 0.0);  // one-shot ended
        // Empty pad and out-of-range pad are ignored.
        c.noteOn(0, 7, 1.0f);
        c.noteOn(0, 99, 1.0f);
        CHECK(energy(renderFrames(c.engine(), 1000).left, 0, 1000) == 0.0);
    });

    test("velocity, pad volume and pan shape the level", [] {
        EngineController c(kRate);
        loadBurst(c, 0, 900);
        c.noteOn(0, 0, 1.0f);
        const float full = renderFrames(c.engine(), 1000).left[500];
        c.noteOn(0, 0, 0.5f);
        const float half = renderFrames(c.engine(), 1000).left[500];
        CHECK_NEAR(half / full, 0.5, 0.01);
        c.setPadParam(0, kPadPan, 1.0f);
        pump(c);
        c.noteOn(0, 0, 1.0f);
        auto r = renderFrames(c.engine(), 1000);
        CHECK(std::fabs(r.left[500]) < 1e-3f);
        CHECK(r.right[500] > 0.35f);
    });

    test("gate pads stop on release, one-shot pads do not", [] {
        EngineController c(kRate);
        loadBurst(c, 0, 48000);
        c.setPadParam(0, kPadGate, 1.0f);
        c.noteOn(0, 0, 1.0f);
        renderFrames(c.engine(), 1000);
        c.noteOff(0, 0);
        auto r = renderFrames(c.engine(), 4000);
        CHECK(energy(r.left, 2000, 4000) == 0.0);

        loadBurst(c, 1, 48000);
        c.noteOn(0, 1, 1.0f);
        renderFrames(c.engine(), 1000);
        c.noteOff(0, 1);
        auto r2 = renderFrames(c.engine(), 4000);
        CHECK(energy(r2.left, 2000, 4000) > 0.0);
    });

    test("choke group: closed hat cuts the open hat", [] {
        EngineController c(kRate);
        loadBurst(c, 0, 48000);
        loadBurst(c, 1, 100);
        c.setPadParam(0, kPadChokeGroup, 1.0f);
        c.setPadParam(1, kPadChokeGroup, 1.0f);
        c.noteOn(0, 0, 1.0f);
        renderFrames(c.engine(), 1000);
        c.noteOn(0, 1, 1.0f);
        auto r = renderFrames(c.engine(), 4000);
        CHECK(energy(r.left, 2000, 4000) == 0.0);
    });

    test("pattern notes land on the beat, once per loop, regardless of block size", [] {
        EngineController c(kRate);
        loadBurst(c, 0);
        c.setTempo(120);
        c.setTrackLength(0, 2.0);
        c.setPattern(0, {NoteEvent{1.0, 0.25, 0, 1.0f, 0}});
        c.setMetronome(false, 0.0f);
        c.play(0.0);
        auto r = renderFrames(c.engine(), static_cast<int64_t>(6 * kFramesPerBeat120));
        const auto on = onsets(r.left);
        CHECK(on.size() == 3);
        if (on.size() == 3) {
            CHECK_NEAR(static_cast<double>(on[0]), 1 * kFramesPerBeat120, 2.0);
            CHECK_NEAR(static_cast<double>(on[1]), 3 * kFramesPerBeat120, 2.0);
            CHECK_NEAR(static_cast<double>(on[2]), 5 * kFramesPerBeat120, 2.0);
        }
        CHECK_NEAR(c.engine().beatPosition(), 6.0, 1e-6);
    });

    test("tracks with different clip lengths loop independently", [] {
        EngineController c(kRate);
        loadBurst(c, 0);
        c.setMetronome(false, 0.0f);
        c.setTrackParam(0, kTrackPan, -1.0f);
        c.setTrackParam(1, kTrackPan, 1.0f);
        c.setTrackLength(0, 1.0);  // every beat
        c.setTrackLength(1, 3.0);  // every third beat
        c.setPattern(0, {NoteEvent{0.0, 0.1, 0, 1.0f, 0}});
        c.setPattern(1, {NoteEvent{0.0, 0.1, 0, 1.0f, 0}});
        pump(c, 1);  // let the pan ramp settle so the hard-panned tracks do not bleed
        c.play(0.0);
        auto r = renderFrames(c.engine(), static_cast<int64_t>(6 * kFramesPerBeat120));
        CHECK(onsets(r.left).size() == 6);   // beats 0..5
        CHECK(onsets(r.right).size() == 2);  // beats 0 and 3
    });

    test("a note recorded just ahead of the play head is skipped once", [] {
        EngineController c(kRate);
        loadBurst(c, 0);
        c.setMetronome(false, 0.0f);
        c.setTrackLength(0, 1.0);
        c.setPattern(0, {NoteEvent{0.5, 0.1, 0, 1.0f, kNoteSkipOnce}});
        c.play(0.0);
        auto r = renderFrames(c.engine(), static_cast<int64_t>(3 * kFramesPerBeat120));
        const auto on = onsets(r.left);
        CHECK(on.size() == 2);
        if (!on.empty()) CHECK_NEAR(static_cast<double>(on[0]), 1.5 * kFramesPerBeat120, 2.0);
    });

    test("count-in clicks without playing clips, then starts at beat 0", [] {
        EngineController c(kRate);
        loadBurst(c, 0);
        c.setMetronome(false, 0.0f);  // count-in clicks anyway
        c.setTrackLength(0, 4.0);
        c.setPattern(0, {NoteEvent{0.0, 0.1, 0, 1.0f, 0}});
        c.setTrackParam(0, kTrackPan, 1.0f);  // pattern on the right only
        pump(c);
        c.play(4.0);
        CHECK(c.engine().beatPosition() == 0.0);  // not rendered yet
        auto r = renderFrames(c.engine(), static_cast<int64_t>(5 * kFramesPerBeat120));
        const auto clicks = onsets(r.left);  // clicks are centred, the pattern is hard right
        CHECK(clicks.size() == 4);
        if (clicks.size() == 4) CHECK_NEAR(static_cast<double>(clicks[1] - clicks[0]), kFramesPerBeat120, 2.0);
        const auto right = onsets(r.right);
        CHECK(right.size() == 5);
        if (right.size() == 5) CHECK_NEAR(static_cast<double>(right[4]), 4 * kFramesPerBeat120, 2.0);
    });

    test("stop resets the play head and silences the sequence", [] {
        EngineController c(kRate);
        loadBurst(c, 0, 48000);
        c.setMetronome(false, 0.0f);
        c.setPattern(0, {NoteEvent{0.0, 4.0, 0, 1.0f, 0}});
        c.setPadParam(0, kPadGate, 1.0f);
        c.play(0.0);
        renderFrames(c.engine(), 2000);
        c.stop();
        auto r = renderFrames(c.engine(), 4000);
        CHECK(energy(r.left, 2000, 4000) == 0.0);
        CHECK(c.engine().beatPosition() == 0.0);
        CHECK(!c.engine().isPlaying());
    });

    test("synth track plays MIDI notes with release", [] {
        EngineController c(kRate);
        c.setTrackType(1, static_cast<int32_t>(TrackType::Synth));
        c.setTrackParam(1, kSynthRelease, 0.05f);
        c.noteOn(1, 69, 1.0f);
        auto held = renderFrames(c.engine(), 4800);
        CHECK(energy(held.left, 2400, 4800) > 1.0);
        c.noteOff(1, 69);
        auto released = renderFrames(c.engine(), 9600);
        CHECK(energy(released.left, 7200, 9600) == 0.0);
        CHECK(c.engine().activeVoices() == 0);
    });

    test("synth track can play a pad sample chromatically", [] {
        EngineController c(kRate);
        std::vector<float> saw(4800);
        for (size_t i = 0; i < saw.size(); ++i) saw[i] = static_cast<float>(i % 100) / 100.0f - 0.5f;
        c.setPadSample(5, saw.data(), 4800, 1, kRate);
        c.setTrackType(1, static_cast<int32_t>(TrackType::Synth));
        c.setTrackParam(1, kSynthSourcePad, 5.0f);
        c.noteOn(1, 72, 1.0f);  // an octave up: plays twice as fast
        auto r = renderFrames(c.engine(), 4800);
        const auto on = onsets(r.left, 1e-4f, 50);
        CHECK(!on.empty());
        CHECK(energy(r.left, 2500, 4800) < 1e-6);  // 4800 frames at 2x rate end after 2400
    });

    test("mute and solo", [] {
        EngineController c(kRate);
        loadBurst(c, 0, 2000);
        c.setTrackParam(0, kTrackMute, 1.0f);
        pump(c);
        c.noteOn(0, 0, 1.0f);
        CHECK(energy(renderFrames(c.engine(), 1000).left, 0, 1000) == 0.0);
        c.setTrackParam(0, kTrackMute, 0.0f);
        c.setTrackParam(1, kTrackSolo, 1.0f);
        pump(c);
        c.noteOn(0, 0, 1.0f);
        CHECK(energy(renderFrames(c.engine(), 1000).left, 0, 1000) == 0.0);
        c.setTrackParam(1, kTrackSolo, 0.0f);
        pump(c);
        c.noteOn(0, 0, 1.0f);
        CHECK(energy(renderFrames(c.engine(), 1000).left, 0, 1000) > 0.0);
    });

    test("delay effect repeats a hit after the synced delay time", [] {
        EngineController c(kRate);
        loadBurst(c, 0, 200);
        c.setTrackParam(0, kFxDelayOn, 1.0f);
        c.setTrackParam(0, kFxDelayBeats, 0.25f);  // 6000 frames at 120 bpm
        c.setTrackParam(0, kFxDelayFeedback, 0.0f);
        c.setTrackParam(0, kFxDelayMix, 1.0f);
        pump(c);
        c.noteOn(0, 0, 1.0f);
        auto r = renderFrames(c.engine(), 12000);
        const auto on = onsets(r.left);
        CHECK(on.size() == 2);
        if (on.size() == 2) CHECK_NEAR(static_cast<double>(on[1] - on[0]), 6000.0, 4.0);
    });

    test("low-pass filter removes high frequencies, reverb adds a tail", [] {
        EngineController c(kRate);
        std::vector<float> hiss(9600);
        uint32_t seed = 1;
        for (auto& v : hiss) { seed = seed * 1664525u + 1013904223u; v = static_cast<float>(seed >> 8) / 16777216.0f - 0.5f; }
        c.setPadSample(0, hiss.data(), 9600, 1, kRate);
        c.noteOn(0, 0, 1.0f);
        auto dry = renderFrames(c.engine(), 9600);
        c.setTrackParam(0, kFxFilterOn, 1.0f);
        c.setTrackParam(0, kFxFilterCutoff, 300.0f);
        pump(c);
        c.noteOn(0, 0, 1.0f);
        auto filtered = renderFrames(c.engine(), 9600);
        CHECK(energy(filtered.left, 1000, 9000) < energy(dry.left, 1000, 9000) * 0.2);

        c.setTrackParam(0, kFxFilterOn, 0.0f);
        c.setTrackParam(0, kFxReverbOn, 1.0f);
        c.setTrackParam(0, kFxReverbMix, 0.8f);
        loadBurst(c, 1, 200);
        pump(c, 20);
        c.noteOn(0, 1, 1.0f);
        auto wet = renderFrames(c.engine(), 24000);
        CHECK(energy(wet.left, 4000, 24000) > 1e-4);
    });

    test("limiter keeps the mix inside [-1, 1]", [] {
        EngineController c(kRate);
        auto loud = burst(4800, 1.0f);
        for (int p = 0; p < kMaxPads; ++p) c.setPadSample(p, loud.data(), 4800, 1, kRate);
        c.setTrackParam(0, kTrackVolume, 1.5f);
        for (int p = 0; p < kMaxPads; ++p) c.noteOn(0, p, 1.0f);
        auto r = renderFrames(c.engine(), 4800);
        float peak = 0.0f;
        for (float v : r.left) peak = std::max(peak, std::fabs(v));
        CHECK(peak <= 1.0f);
        CHECK(peak > 0.8f);
    });

    test("audio recording starts on the clip boundary, compensates latency and loops", [] {
        EngineController c(kRate);
        c.setMetronome(false, 0.0f);
        c.setTrackType(2, static_cast<int32_t>(TrackType::Audio));
        c.setTrackLength(2, 1.0);
        c.setInputLatencyFrames(100);
        CHECK(c.armAudioRecord(2, 1.0));
        c.play(0.0);
        int64_t clock = 0;
        // Input value encodes the frame it was captured at.
        auto input = [](int64_t frame) { return static_cast<float>(frame % 1000) / 1000.0f; };
        renderFrames(c.engine(), static_cast<int64_t>(1.5 * kFramesPerBeat120), input, &clock);
        auto events = c.pollEvents();
        CHECK(events.size() == 2);
        if (events.size() == 2) {
            CHECK(events[0].type == EngineEventType::AudioRecordStarted);
            CHECK(events[1].type == EngineEventType::AudioRecordFinished);
            CHECK(events[1].track == 2);
        }
        double beats = 0.0;
        auto clip = c.copyAudioClip(2, &beats);
        CHECK_NEAR(static_cast<double>(clip.size()), kFramesPerBeat120, 1.0);
        CHECK_NEAR(beats, 1.0, 1e-9);
        if (!clip.empty()) {
            CHECK_NEAR(clip[0], input(100), 1e-6);  // first 100 frames were latency
            CHECK_NEAR(clip[500], input(600), 1e-6);
            CHECK_NEAR(clip.back(), input(100 + static_cast<int64_t>(clip.size()) - 1), 1e-6);  // latency tail kept
        }
        // The clip now plays on the track every loop.
        auto r = renderFrames(c.engine(), static_cast<int64_t>(kFramesPerBeat120), nullptr);
        CHECK(energy(r.left, 0, r.left.size()) > 1.0);
        CHECK(c.engine().recordingTrack() == -1);
    });

    test("audio clips can be loaded, replaced and cleared without leaks", [] {
        EngineController c(kRate);
        c.setMetronome(false, 0.0f);
        c.setTrackType(3, static_cast<int32_t>(TrackType::Audio));
        c.setTrackLength(3, 2.0);
        auto tone = burst(static_cast<int>(2 * kFramesPerBeat120), 0.3f);
        CHECK(c.setAudioClip(3, tone.data(), static_cast<int64_t>(tone.size()), kRate, 2.0));
        CHECK(c.setAudioClip(3, tone.data(), static_cast<int64_t>(tone.size()), kRate, 2.0));
        c.play(0.0);
        auto r = renderFrames(c.engine(), 4800);
        CHECK(r.left[2400] > 0.2f);
        double beats = 0;
        CHECK(c.copyAudioClip(3, &beats).size() == tone.size());
        c.clearAudioClip(3);
        pump(c);
        CHECK(c.copyAudioClip(3, &beats).empty());
        CHECK(energy(renderFrames(c.engine(), 4800).left, 0, 4800) == 0.0);
    });

    test("cancelling or stopping before the take starts discards it", [] {
        EngineController c(kRate);
        c.setTrackType(2, static_cast<int32_t>(TrackType::Audio));
        CHECK(c.armAudioRecord(2, 4.0));
        pump(c);
        CHECK(c.engine().recordingTrack() == 2);
        CHECK(!c.engine().recordingActive());
        c.cancelAudioRecord();
        pump(c);
        CHECK(c.engine().recordingTrack() == -1);
        CHECK(c.pollEvents().empty());
    });

    test("mic capture for sampling, with a live audio thread", [] {
        EngineController c(kRate);
        std::atomic<bool> running{true};
        std::thread audio([&] {
            std::vector<float> out(512), in(256, 0.25f);
            while (running.load()) {
                c.engine().render(out.data(), in.data(), 256);
                std::this_thread::sleep_for(std::chrono::microseconds(200));
            }
        });
        CHECK(c.startCapture(2.0f));
        std::this_thread::sleep_for(std::chrono::milliseconds(30));
        auto captured = c.stopCapture(500);
        running = false;
        audio.join();
        CHECK(captured.size() >= 256);
        CHECK(!captured.empty() && captured[0] == 0.25f);
    });

    test("stopCapture with no audio thread times out cleanly", [] {
        EngineController c(kRate);
        CHECK(c.startCapture(1.0f));
        auto captured = c.stopCapture(20);
        CHECK(captured.empty());
        pump(c);  // engine hands the buffer back now; the controller frees it
        CHECK(c.pollEvents().empty());
    });

    test("replacing samples while voices play frees the old one safely", [] {
        EngineController c(kRate);
        for (int i = 0; i < 50; ++i) {
            loadBurst(c, 0, 48000);
            c.noteOn(0, 0, 1.0f);
            c.padLoop(0, 0, true);
            pump(c, 2);
        }
        c.clearPad(0);
        pump(c);
        c.pollEvents();
        CHECK(c.engine().activeVoices() == 0);
    });

    std::printf("\n%d checks, %d failures\n", gChecks, gFailures);
    return gFailures == 0 ? 0 : 1;
}
