use alass_core::{align_nosplit, standard_scoring, NoProgressHandler, TimePoint, TimeSpan};
use jni::objects::{JClass, JLongArray, JShortArray};
use jni::sys::{jdoubleArray, jbyteArray};
use jni::JNIEnv;
use webrtc_vad::{SampleRate, Vad};

fn spans(values: &[i64]) -> Vec<TimeSpan> {
    values
        .chunks_exact(2)
        .filter(|pair| pair[0] >= 0 && pair[1] > pair[0])
        .map(|pair| TimeSpan::new(TimePoint::from(pair[0]), TimePoint::from(pair[1])))
        .collect()
}

#[no_mangle]
pub extern "system" fn Java_com_saab_tv_data_subtitle_AlassBridge_align(
    env: JNIEnv,
    _class: JClass,
    reference: JLongArray,
    subtitle: JLongArray,
) -> jdoubleArray {
    let mut reference_values = vec![0_i64; env.get_array_length(&reference).unwrap_or(0) as usize];
    let mut subtitle_values = vec![0_i64; env.get_array_length(&subtitle).unwrap_or(0) as usize];
    if env.get_long_array_region(&reference, 0, &mut reference_values).is_err()
        || env.get_long_array_region(&subtitle, 0, &mut subtitle_values).is_err()
    {
        return std::ptr::null_mut();
    }
    let (delta, score) = align_nosplit(
        &spans(&reference_values),
        &spans(&subtitle_values),
        standard_scoring,
        NoProgressHandler,
    );
    match env.new_double_array(2) {
        Ok(result) => {
            if env.set_double_array_region(&result, 0, &[delta.as_i64() as f64, score]).is_ok() {
                result.into_raw()
            } else {
                std::ptr::null_mut()
            }
        }
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_saab_tv_data_subtitle_AlassBridge_speechFrames(
    env: JNIEnv,
    _class: JClass,
    pcm_8khz: JShortArray,
) -> jbyteArray {
    let mut samples = vec![0_i16; env.get_array_length(&pcm_8khz).unwrap_or(0) as usize];
    if env.get_short_array_region(&pcm_8khz, 0, &mut samples).is_err() {
        return std::ptr::null_mut();
    }
    let mut vad = Vad::new_with_rate(SampleRate::Rate8kHz);
    let frames: Vec<i8> = samples
        .chunks_exact(80)
        .map(|frame| vad.is_voice_segment(frame).unwrap_or(false) as i8)
        .collect();
    match env.byte_array_from_slice(&frames.iter().map(|value| *value as u8).collect::<Vec<_>>()) {
        Ok(result) => result.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn alass_delta_shifts_late_subtitles_earlier() {
        let reference = spans(&[1_000, 2_000, 4_000, 5_000, 7_000, 8_000]);
        let late = spans(&[1_500, 2_500, 4_500, 5_500, 7_500, 8_500]);
        let (delta, _) = align_nosplit(&reference, &late, standard_scoring, NoProgressHandler);
        assert_eq!(delta.as_i64(), -500);
    }
}
