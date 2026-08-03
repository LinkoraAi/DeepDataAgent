import { describe, it, expect } from 'vitest';
import { mount } from '@vue/test-utils';
import MessageHeader from '../components/MessageHeader.vue';

const tdesignStubs = {
  't-avatar': { template: '<div class="t-avatar-stub" />' },
};

describe('MessageHeader', () => {
  it('should_displayFormattedTime_when_mounted_given_timestamp', () => {
    // given
    const timestamp = new Date(2026, 6, 23, 14, 30).getTime();

    // when
    const wrapper = mount(MessageHeader, {
      props: { timestamp },
      global: { stubs: tdesignStubs },
    });

    // then
    expect(wrapper.text()).toContain('14:30');
  });

  it('should_displayDuration_when_mounted_given_startAndEndTime', () => {
    // given
    const startTime = 1000000;
    const endTime = 1000000 + 12000; // 12 seconds

    // when
    const wrapper = mount(MessageHeader, {
      props: {
        timestamp: startTime,
        analysisStartTime: startTime,
        analysisEndTime: endTime,
      },
      global: { stubs: tdesignStubs },
    });

    // then
    expect(wrapper.text()).toContain('耗时 12s');
  });

  it('should_notDisplayDuration_when_mounted_given_noEndTime', () => {
    // given
    const startTime = 1000000;

    // when
    const wrapper = mount(MessageHeader, {
      props: {
        timestamp: startTime,
        analysisStartTime: startTime,
        isAnalyzing: true,
      },
      global: { stubs: tdesignStubs },
    });

    // then
    expect(wrapper.text()).not.toContain('耗时');
  });

  it('should_displayAnalyzingLabel_when_mounted_given_isAnalyzingTrue', () => {
    // given
    const timestamp = Date.now();

    // when
    const wrapper = mount(MessageHeader, {
      props: { timestamp, isAnalyzing: true },
      global: { stubs: tdesignStubs },
    });

    // then
    expect(wrapper.text()).toContain('分析中');
  });

  it('should_displayMinutesFormat_when_mounted_given_durationOver60Seconds', () => {
    // given
    const startTime = 1000000;
    const endTime = 1000000 + 75000; // 1m 15s

    // when
    const wrapper = mount(MessageHeader, {
      props: {
        timestamp: startTime,
        analysisStartTime: startTime,
        analysisEndTime: endTime,
      },
      global: { stubs: tdesignStubs },
    });

    // then
    expect(wrapper.text()).toContain('耗时 1m 15s');
  });
});
