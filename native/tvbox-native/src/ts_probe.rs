use jni::objects::{JClass, JByteArray};
use jni::sys::jdoubleArray;
use jni::JNIEnv;

const MAX_BYTES: usize = 64 * 1024;

fn u(data: &[u8], offset: usize) -> usize {
    (data[offset] as usize) & 0xFF
}

#[derive(Debug)]
struct VideoInfo {
    width: i32,
    height: i32,
    pts: f64,
}

fn read_ts_probe(data: &[u8]) -> Option<VideoInfo> {
    if data.len() < 188 || data.len() > MAX_BYTES { return None; }

    let mut video_pid: usize = usize::MAX;
    let mut pts: f64 = -1.0;
    let mut elementary: Vec<u8> = Vec::new();

    let mut packet = 0;
    while packet + 188 <= data.len() {
        let end = packet + 188;
        if u(data, packet) != 0x47 || (u(data, packet + 1) & 0x80) != 0 { return None; }
        let pid = ((u(data, packet + 1) & 31) << 8) | u(data, packet + 2);
        let control = u(data, packet + 3);
        if (control & 0xc0) != 0 { return None; }
        if (control & 0x10) == 0 { packet += 188; continue; }
        let mut pos = packet + 4;
        if (control & 0x20) != 0 { pos += 1 + u(data, pos); }
        if pos >= end { packet += 188; continue; }
        let start = (u(data, packet + 1) & 0x40) != 0;

        if video_pid == usize::MAX && start {
            let table = pos + 1 + u(data, pos);
            if table + 12 <= end && u(data, table) == 2 && (u(data, table + 1) & 0x80) != 0 {
                let section_end = table + 3 + (((u(data, table + 1) & 15) << 8) | u(data, table + 2)) - 4;
                if section_end > end { packet += 188; continue; }
                let mut entry = table + 12 + (((u(data, table + 10) & 15) << 8) | u(data, table + 11));
                while entry + 5 <= section_end {
                    if u(data, entry) == 0x1b {
                        video_pid = ((u(data, entry + 1) & 31) << 8) | u(data, entry + 2);
                        break;
                    }
                    entry += 5 + (((u(data, entry + 3) & 15) << 8) | u(data, entry + 4));
                }
            }
        }

        if pid != video_pid { packet += 188; continue; }

        if start {
            if pos + 9 > end || u(data, pos) != 0 || u(data, pos + 1) != 0 ||
                u(data, pos + 2) != 1 || (u(data, pos + 3) & 0xf0) != 0xe0
            {
                return None;
            }
            if pts < 0.0 {
                if (u(data, pos + 7) & 0x80) == 0 || u(data, pos + 8) < 5 || pos + 14 > end { return None; }
                let p = pos + 9;
                if (u(data, p) & 1) == 0 || (u(data, p + 2) & 1) == 0 || (u(data, p + 4) & 1) == 0 { return None; }
                let ticks =
                    (((u(data, p) & 14) as u64) << 29) | ((u(data, p + 1) as u64) << 22) |
                    (((u(data, p + 2) & 254) as u64) << 14) | ((u(data, p + 3) as u64) << 7) |
                    ((u(data, p + 4) >> 1) as u64);
                pts = ticks as f64 / 90000.0;
            }
            pos += 9 + u(data, pos + 8);
            if pos > end { return None; }
        }

        elementary.extend_from_slice(&data[pos..end]);
        packet += 188;
    }

    if pts < 0.0 { return None; }

    for i in 0..elementary.len().saturating_sub(4) {
        if elementary[i] != 0 || elementary[i + 1] != 0 || elementary[i + 2] != 1 ||
            (elementary[i + 3] & 31) != 7
        {
            continue;
        }
        let mut nal_end = i + 4;
        while nal_end + 3 < elementary.len() &&
            !(
                elementary[nal_end] == 0 && elementary[nal_end + 1] == 0 &&
                (elementary[nal_end + 2] == 1 || (elementary[nal_end + 2] == 0 && elementary[nal_end + 3] == 1))
            )
        {
            nal_end += 1;
        }
        if nal_end + 3 >= elementary.len() { return None; }

        if let Some((width, height)) = parse_sps(&elementary, i + 3, nal_end) {
            return Some(VideoInfo { width, height, pts });
        }
    }

    None
}

fn parse_sps(data: &[u8], start: usize, end: usize) -> Option<(i32, i32)> {
    if start >= end || end > data.len() { return None; }
    let sps_data = &data[start..end];
    if sps_data.len() < 4 { return None; }

    let mut reader = BitReader::new(sps_data);
    reader.skip(8);

    let profile_idc = reader.read_bits(8);
    reader.skip(8);
    let level_idc = reader.read_bits(8);

    let _seq_parameter_set_id = reader.read_ue();

    if profile_idc == 100 || profile_idc == 110 || profile_idc == 122 || profile_idc == 244 ||
        profile_idc == 44 || profile_idc == 83 || profile_idc == 86 || profile_idc == 118 ||
        profile_idc == 128 || profile_idc == 138 || profile_idc == 139 || profile_idc == 134
    {
        let chroma_format_idc = reader.read_ue();
        if chroma_format_idc == 3 { reader.skip(1); }
        reader.read_ue();
        reader.read_ue();
        reader.skip(1);
        let seq_scaling_matrix_present_flag = reader.read_bits(1);
        if seq_scaling_matrix_present_flag != 0 {
            let count = if chroma_format_idc != 3 { 8 } else { 12 };
            for _ in 0..count {
                let seq_scaling_list_present_flag = reader.read_bits(1);
                if seq_scaling_list_present_flag != 0 {
                    let size = 16;
                    let mut last_scale = 8i32;
                    let mut next_scale = 8i32;
                    for j in 0..size {
                        if next_scale != 0 {
                            let delta_scale = reader.read_se();
                            next_scale = (last_scale + delta_scale + 256) % 256;
                        }
                        if next_scale != 0 { last_scale = next_scale; }
                    }
                }
            }
        }
    }

    reader.read_ue();
    let pic_order_cnt_type = reader.read_ue();
    if pic_order_cnt_type == 0 {
        reader.read_ue();
    } else if pic_order_cnt_type == 1 {
        reader.skip(1);
        reader.read_se();
        reader.read_se();
        let num_ref_frames_in_pic_order_cnt_cycle = reader.read_ue();
        for _ in 0..num_ref_frames_in_pic_order_cnt_cycle {
            reader.read_se();
        }
    }

    reader.read_ue();
    reader.skip(1);

    let pic_width_in_mbs_minus1 = reader.read_ue();
    let pic_height_in_map_units_minus1 = reader.read_ue();

    let frame_mbs_only_flag = reader.read_bits(1);
    if frame_mbs_only_flag == 0 { reader.skip(1); }

    reader.skip(1);

    let mut crop_left = 0u32;
    let mut crop_right = 0u32;
    let mut crop_top = 0u32;
    let mut crop_bottom = 0u32;

    let frame_cropping_flag = reader.read_bits(1);
    if frame_cropping_flag != 0 {
        crop_left = reader.read_ue();
        crop_right = reader.read_ue();
        crop_top = reader.read_ue();
        crop_bottom = reader.read_ue();
    }

    let width = ((pic_width_in_mbs_minus1 + 1) as i32) * 16 - (crop_left + crop_right) as i32 * 2;
    let height = ((2 - frame_mbs_only_flag as i32) * (pic_height_in_map_units_minus1 as i32 + 1)) * 16
        - (crop_top + crop_bottom) as i32 * 2;

    Some((width, height))
}

struct BitReader<'a> {
    data: &'a [u8],
    byte_pos: usize,
    bit_pos: u8,
}

impl<'a> BitReader<'a> {
    fn new(data: &'a [u8]) -> Self {
        BitReader { data, byte_pos: 0, bit_pos: 0 }
    }

    fn skip(&mut self, bits: u32) {
        for _ in 0..bits {
            self.bit_pos += 1;
            if self.bit_pos >= 8 {
                self.bit_pos = 0;
                self.byte_pos += 1;
            }
        }
    }

    fn read_bits(&mut self, bits: u32) -> u32 {
        let mut result = 0u32;
        for _ in 0..bits {
            if self.byte_pos >= self.data.len() { return result; }
            let bit = (self.data[self.byte_pos] >> (7 - self.bit_pos)) & 1;
            result = (result << 1) | bit as u32;
            self.bit_pos += 1;
            if self.bit_pos >= 8 {
                self.bit_pos = 0;
                self.byte_pos += 1;
            }
        }
        result
    }

    fn read_ue(&mut self) -> u32 {
        let mut leading_zeros = 0u32;
        while self.read_bits(1) == 0 && leading_zeros < 32 {
            leading_zeros += 1;
        }
        if leading_zeros >= 32 { return 0; }
        let value = self.read_bits(leading_zeros);
        value + (1 << leading_zeros) - 1
    }

    fn read_se(&mut self) -> i32 {
        let ue = self.read_ue();
        if ue % 2 == 0 {
            -((ue / 2) as i32)
        } else {
            ((ue + 1) / 2) as i32
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_wuming_tv_core_playback_TsStreamProbe_nativeProbe(
    mut env: JNIEnv,
    _class: JClass,
    data: JByteArray,
) -> jdoubleArray {
    let data_bytes = match env.convert_byte_array(&data) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };

    match read_ts_probe(&data_bytes) {
        Some(info) => {
            let result: [f64; 3] = [info.width as f64, info.height as f64, info.pts];
            match env.new_double_array(3) {
                Ok(arr) => {
                    let _ = env.set_double_array_region(&arr, 0, &result);
                    arr.into_raw()
                }
                Err(_) => std::ptr::null_mut(),
            }
        }
        None => std::ptr::null_mut(),
    }
}
