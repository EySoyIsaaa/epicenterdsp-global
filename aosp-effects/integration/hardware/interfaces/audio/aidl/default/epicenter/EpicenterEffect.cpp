/*
 * AOSP Effects AIDL adapter for the unmodified EpicenterDSP engines.
 * SPDX-License-Identifier: Apache-2.0
 */
#define LOG_TAG "AHAL_Epicenter"

#include "EpicenterEffect.h"

#include <android-base/properties.h>
#include <algorithm>
#include <array>
#include <cstddef>
#include <system/audio_effects/effect_uuid.h>

namespace aidl::android::hardware::audio::effect {
namespace {
constexpr int kMaximumBlockFrames = 8192;
constexpr int kMinimumStrength = 0;
constexpr int kMaximumStrength = 1000;
const std::vector<Range::BassBoostRange> kRanges = {
        MAKE_RANGE(BassBoost, strengthPm, kMinimumStrength, kMaximumStrength)};
}  // namespace

AudioUuid epicenterImplUuid() {
    return AudioUuid{0xd05a47cc, 0x8be1, 0x4a58, 0x9fb5,
                     {0x13, 0x92, 0x93, 0x7d, 0x6b, 0xca}};
}

AudioUuid bassBoostTypeUuid() {
    // Android's standard BassBoost effect type UUID (not an implementation UUID).
    return AudioUuid{0x0634f220, 0xddd4, 0x11db, 0xa0fc,
                     {0x00, 0x02, 0xa5, 0xd5, 0xc5, 0x1b}};
}

EpicenterContext::EpicenterContext(const Parameter::Common& common, bool headphones)
    : EffectContext(1 /* status FMQ depth */, common),
      mHeadphones(headphones),
      mChannels(static_cast<int>(getInputFrameSize() / sizeof(float))),
      mLeft(kMaximumBlockFrames),
      mRight(kMaximumBlockFrames) {
    const double sampleRate = common.input.base.sampleRate > 0
                                      ? common.input.base.sampleRate
                                      : 44100.0;
    mChannels = std::clamp(mChannels, 1, 2);
    mPlanes[0] = mLeft.data();
    mPlanes[1] = mRight.data();
    mClassic.prepare(sampleRate, mChannels, kMaximumBlockFrames);
    mClassic.setEnabled(true);
    mClassic.setParameters(100.0f, 45.0f, 50.0f, 100.0f, 100.0f);
    mHeadphonesCore.prepare(sampleRate, mChannels);
    mHeadphonesCore.setIntensity(100.0f);
}

RetCode EpicenterContext::setStrength(int strengthPm) {
    mStrengthPm = strengthPm;
    const float intensity = static_cast<float>(strengthPm) / 10.0f;
    mClassic.setEnabled(strengthPm > 0);
    mClassic.setParameters(intensity, 45.0f, 50.0f, 100.0f, 100.0f);
    mHeadphonesCore.setIntensity(intensity);
    return RetCode::SUCCESS;
}

void EpicenterContext::process(float* input, float* output, int samples) {
    if (!input || !output || samples <= 0) return;
    const int channels = std::max(1, mChannels);
    const int usableSamples = samples - (samples % channels);
    int offset = 0;
    while (offset < usableSamples) {
        const int frames = std::min(kMaximumBlockFrames, (usableSamples - offset) / channels);
        for (int frame = 0; frame < frames; ++frame) {
            const int base = offset + frame * channels;
            mLeft[frame] = input[base];
            if (channels == 2) mRight[frame] = input[base + 1];
        }
        if (mStrengthPm == 0) {
            for (int frame = 0; frame < frames; ++frame) {
                const int base = offset + frame * channels;
                output[base] = mLeft[frame];
                if (channels == 2) output[base + 1] = mRight[frame];
            }
        } else {
            if (mHeadphones) {
                mHeadphonesCore.process(mPlanes, channels, static_cast<size_t>(frames));
            } else {
                mClassic.process(mPlanes, channels, static_cast<size_t>(frames));
            }
            for (int frame = 0; frame < frames; ++frame) {
                const int base = offset + frame * channels;
                output[base] = mLeft[frame];
                if (channels == 2) output[base + 1] = mRight[frame];
            }
        }
        offset += frames * channels;
    }
    // Preserve any malformed trailing samples as passthrough; standard FMQ blocks
    // are expected to contain whole frames.
    for (int i = usableSamples; i < samples; ++i) output[i] = input[i];
}

RetCode EpicenterContext::reset() {
    mClassic.reset();
    mHeadphonesCore.reset();
    return EffectContext::reset();
}

const std::string EpicenterEffect::kEffectName = "EpicenterDSP";
const Capability EpicenterEffect::kCapability = {.range = {kRanges}};
const Descriptor EpicenterEffect::kDescriptor = {
        .common = {.id = {.type = bassBoostTypeUuid(), .uuid = epicenterImplUuid()},
                   .flags = {.type = Flags::Type::INSERT,
                             .insert = Flags::Insert::ANY,
                             .volume = Flags::Volume::NONE},
                   .name = kEffectName,
                   .implementor = "EpicenterDSP"},
        .capability = kCapability};

ndk::ScopedAStatus EpicenterEffect::getDescriptor(Descriptor* desc) {
    if (!desc) return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
    *desc = kDescriptor;
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus EpicenterEffect::setParameterSpecific(const Parameter::Specific& specific) {
    RETURN_IF(Parameter::Specific::bassBoost != specific.getTag(), EX_ILLEGAL_ARGUMENT,
              "EffectNotSupported");
    RETURN_IF(!mContext, EX_NULL_POINTER, "nullContext");
    const auto& bassBoost = specific.get<Parameter::Specific::bassBoost>();
    RETURN_IF(!inRange(bassBoost, kRanges), EX_ILLEGAL_ARGUMENT, "outOfRange");
    RETURN_IF(bassBoost.getTag() != BassBoost::strengthPm, EX_ILLEGAL_ARGUMENT,
              "BassBoostTagNotSupported");
    RETURN_IF(mContext->setStrength(bassBoost.get<BassBoost::strengthPm>()) != RetCode::SUCCESS,
              EX_ILLEGAL_ARGUMENT, "strengthNotSupported");
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus EpicenterEffect::getParameterSpecific(const Parameter::Id& id,
                                                         Parameter::Specific* specific) {
    RETURN_IF(!specific, EX_ILLEGAL_ARGUMENT, "nullSpecific");
    RETURN_IF(id.getTag() != Parameter::Id::bassBoostTag, EX_ILLEGAL_ARGUMENT, "wrongIdTag");
    const auto& bassBoostId = id.get<Parameter::Id::bassBoostTag>();
    RETURN_IF(bassBoostId.getTag() != BassBoost::Id::commonTag, EX_ILLEGAL_ARGUMENT,
              "wrongBassBoostIdTag");
    return getBassBoostParameter(bassBoostId.get<BassBoost::Id::commonTag>(), specific);
}

ndk::ScopedAStatus EpicenterEffect::getBassBoostParameter(const BassBoost::Tag& tag,
                                                          Parameter::Specific* specific) {
    RETURN_IF(!mContext, EX_NULL_POINTER, "nullContext");
    RETURN_IF(tag != BassBoost::strengthPm, EX_ILLEGAL_ARGUMENT, "BassBoostTagNotSupported");
    BassBoost value;
    value.set<BassBoost::strengthPm>(mContext->getStrength());
    specific->set<Parameter::Specific::bassBoost>(value);
    return ndk::ScopedAStatus::ok();
}

std::shared_ptr<EffectContext> EpicenterEffect::createContext(const Parameter::Common& common) {
    if (!mContext) {
        const auto inputChannels =
                ::aidl::android::hardware::audio::common::getChannelCount(
                        common.input.base.channelMask);
        const auto outputChannels =
                ::aidl::android::hardware::audio::common::getChannelCount(
                        common.output.base.channelMask);
        if (inputChannels == 0 || inputChannels > 2 || inputChannels != outputChannels) {
            return nullptr;
        }
        const auto selected = ::android::base::GetProperty("ro.vendor.epicenter.engine", "classic");
        mContext = std::make_shared<EpicenterContext>(common, selected == "headphones");
    }
    return mContext;
}

RetCode EpicenterEffect::releaseContext() {
    mContext.reset();
    return RetCode::SUCCESS;
}

IEffect::Status EpicenterEffect::effectProcessImpl(float* in, float* out, int samples) {
    if (!mContext || samples < 0) return {STATUS_BAD_VALUE, 0, 0};
    mContext->process(in, out, samples);
    return {STATUS_OK, samples, samples};
}

}  // namespace aidl::android::hardware::audio::effect

extern "C" binder_exception_t createEffect(
        const aidl::android::media::audio::common::AudioUuid* implUuid,
        std::shared_ptr<aidl::android::hardware::audio::effect::IEffect>* instance) {
    using namespace aidl::android::hardware::audio::effect;
    if (!implUuid || !instance || *implUuid != epicenterImplUuid()) return EX_ILLEGAL_ARGUMENT;
    *instance = ndk::SharedRefBase::make<EpicenterEffect>();
    return EX_NONE;
}

extern "C" binder_exception_t queryEffect(
        const aidl::android::media::audio::common::AudioUuid* implUuid,
        aidl::android::hardware::audio::effect::Descriptor* descriptor) {
    using namespace aidl::android::hardware::audio::effect;
    if (!implUuid || !descriptor || *implUuid != epicenterImplUuid()) return EX_ILLEGAL_ARGUMENT;
    *descriptor = EpicenterEffect::kDescriptor;
    return EX_NONE;
}
