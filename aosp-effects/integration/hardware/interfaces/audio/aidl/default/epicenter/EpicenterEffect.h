#pragma once

#include <aidl/android/hardware/audio/effect/BnEffect.h>
#include <aidl/android/media/audio/common/AudioUuid.h>
#include <memory>
#include <mutex>
#include <vector>

#include "effect-impl/EffectImpl.h"
#include "EpicenterDSPCore.hpp"
#include "EpicenterHeadphonesCore.hpp"

namespace aidl::android::hardware::audio::effect {

class EpicenterContext final : public EffectContext {
  public:
    EpicenterContext(const Parameter::Common& common, bool headphones);
    RetCode setStrength(int strengthPm);
    int getStrength() const { return mStrengthPm; }
    void process(float* input, float* output, int samples);
    RetCode reset() override;

  private:
    bool mHeadphones;
    int mChannels;
    int mStrengthPm = 1000;
    epicenter::EpicenterDSPCore mClassic;
    epicenter::HeadphonesBassCore mHeadphonesCore;
    std::vector<float> mLeft;
    std::vector<float> mRight;
    float* mPlanes[2] = {nullptr, nullptr};
};

class EpicenterEffect final : public EffectImpl {
  public:
    static const std::string kEffectName;
    static const Capability kCapability;
    static const Descriptor kDescriptor;

    ndk::ScopedAStatus getDescriptor(Descriptor* desc) override;
    ndk::ScopedAStatus setParameterSpecific(const Parameter::Specific& specific) override;
    ndk::ScopedAStatus getParameterSpecific(const Parameter::Id& id,
                                            Parameter::Specific* specific) override;
    std::shared_ptr<EffectContext> createContext(const Parameter::Common& common) override;
    RetCode releaseContext() override;
    std::string getEffectName() override { return kEffectName; }
    IEffect::Status effectProcessImpl(float* in, float* out, int samples) override;

  private:
    ndk::ScopedAStatus getBassBoostParameter(const BassBoost::Tag& tag,
                                             Parameter::Specific* specific);
    std::shared_ptr<EpicenterContext> mContext;
};

AudioUuid epicenterImplUuid();
AudioUuid bassBoostTypeUuid();

}  // namespace aidl::android::hardware::audio::effect
