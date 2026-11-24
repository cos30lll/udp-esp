"""Recovered camera equations for fixed UpdateScript_500.dll, not live measurements.
No process access, network access, state defaults, or assumed camera transforms.
All dynamic arguments must be supplied explicitly from verified sources.
"""
import math

RULE_ID = 'ugc2_1201_fixed_1.35.20.001_r1'
EVIDENCE = {
    'factory': '0x06000823 IL_0296..IL_0314',
    'apply_configuration': '0x060059E6 IL_0076..IL_010B',
    'distance': '0x06005A7D IL_0000..IL_0046; 0x060059AE',
    'tps_geometry': '0x06005A43 IL_045C..IL_0914',
    'fps_fov': '0x06005AAB IL_06E4..IL_06FE; 0x060059F9',
    'tps_fov': '0x06005A43 IL_05E2..IL_0622',
    'model_anchor': '0x060165D9 IL_030A..IL_0335',
}

def _number(value, name):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        raise ValueError('missing_or_invalid_' + name)
    return float(value)

def _vector(value, name):
    if not isinstance(value, dict):
        raise ValueError('missing_or_invalid_' + name)
    return {k: _number(value.get(k), name + '_' + k) for k in 'xyz'}

def _lerp(previous, target, factor):
    factor = min(1.0, max(0.0, factor))
    return previous + (target - previous) * factor

def _rotate(rotation, vector):
    if not isinstance(rotation, dict):
        raise ValueError('missing_or_invalid_base_rotation')
    q = {k: _number(rotation.get(k), 'base_rotation_' + k) for k in 'xyzw'}
    norm = sum(v * v for v in q.values())
    if abs(norm - 1.0) > 0.001:
        raise ValueError('base_rotation_must_be_unit_quaternion')
    x, y, z, w = (q[k] for k in 'xyzw')
    vx, vy, vz = (vector[k] for k in 'xyz')
    tx, ty, tz = 2 * (y * vz - z * vy), 2 * (z * vx - x * vz), 2 * (x * vy - y * vx)
    return {'x': vx + w * tx + y * tz - z * ty,
            'y': vy + w * ty + z * tx - x * tz,
            'z': vz + w * tz + x * ty - y * tx}

def base_rule(gameplay_type, gameplay_id):
    if type(gameplay_type) is not int or type(gameplay_id) is not int or (gameplay_type, gameplay_id) != (12, 1201):
        return None
    return {
        'rule_id': RULE_ID, 'source': 'fixed_local_assembly_not_udp_measurement',
        'status': 'static_base_configuration_only',
        'tps_base_fov_deg': 55.0, 'fps_base_fov_deg': 66.0,
        'ugc2_distance_setting': 3.5, 'ugc2_offset_setting': 2.5,
        'pitch_min_deg': -56.0, 'pitch_max_deg': 56.0,
        'runtime_perspective': None, 'final_fov_deg': None, 'final_origin': None,
        'distance_setting_is_not_final_boom_distance': True,
        'evidence': dict(EVIDENCE),
    }

def configured_distance(original_distance, expand_number, distance_scale):
    """0x06005A7D: min(42, CaODis) * expand_number * OBLKMOGMMID."""
    original = _number(original_distance, 'original_distance')
    expand = _number(expand_number, 'expand_number')
    scale = _number(distance_scale, 'distance_scale')
    return min(42.0, original) * expand * scale

def fov_step(*, perspective, previous_fov, base_fov, actor_delta, shot_delta,
             special_delta, delta_time, fov_lerp, special_active,
             special_blend_time, override_active, override_fov):
    """One recovered interpolation step; before animation/render overrides."""
    if type(perspective) is not int or perspective not in (0, 1):
        raise ValueError('confirmed_perspective_required')
    if type(special_active) is not bool or type(override_active) is not bool:
        raise ValueError('explicit_fov_branch_flags_required')
    if perspective == 1 and override_active:
        return _number(override_fov, 'override_fov')
    dt = _number(delta_time, 'delta_time')
    if dt < 0:
        raise ValueError('negative_delta_time')
    if special_active:
        blend = _number(special_blend_time, 'special_blend_time')
        if blend <= 0:
            raise ValueError('positive_special_blend_time_required')
        factor = dt / blend
    else:
        factor = dt * _number(fov_lerp, 'fov_lerp')
    target = (_number(base_fov, 'base_fov') + _number(actor_delta, 'actor_delta')
              + _number(shot_delta, 'shot_delta') + _number(special_delta, 'special_delta'))
    return _lerp(_number(previous_fov, 'previous_fov'), target, factor)

def tps_geometry(*, lerped_pivot, base_rotation, pitch_deg, hangle_deg, distance):
    """0x06005A43 IL_07BA..IL_082D; before correction/collision/swimming.
    lerped_pivot must already include the native actor-up, shoulder, posture,
    local modifiers and render interpolation. It is NOT the UDP actor position.
    """
    pivot = _vector(lerped_pivot, 'lerped_pivot')
    pitch = math.radians(_number(pitch_deg, 'pitch_deg'))
    hangle = math.radians(_number(hangle_deg, 'hangle_deg'))
    length = _number(distance, 'distance')
    horizontal = length * math.cos(pitch)
    boom = _rotate(base_rotation, {'x': horizontal * math.sin(hangle),
                                 'y': length * math.sin(pitch),
                                 'z': -horizontal * math.cos(hangle)})
    return {
        'status': 'pre_collision_geometry_not_final_camera',
        'origin_before_corrections': {k: pivot[k] + boom[k] for k in 'xyz'},
        'evidence': EVIDENCE['tps_geometry'],
        'remaining': ['NJNAIIOLKFJ_correction', 'scene_collision', 'swim_height',
                      'CameraUpdate_animation_and_render_overrides'],
    }

def required_inputs(perspective):
    common = ['verified_render_mode', 'render_base_rotation_and_roll',
              'render_delta_time_and_previous_state', 'actor_fov_modifier',
              'shot_and_special_camera_state', 'animation_camera_override',
              'scene_collision_and_swim_height', 'effective_game_viewport']
    if perspective == 0 and type(perspective) is int:
        return ['model_CameraLook_world_transform', 'FixOffsetPos',
                'first_person_local_offset'] + common
    if perspective == 1 and type(perspective) is int:
        return ['native_lerped_pivot', 'distance_original_and_scale',
                'posture_and_actor_up', 'shoulder_and_local_offsets'] + common
    return ['local_perspective_not_confirmed'] + common
