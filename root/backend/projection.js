'use strict';
/* Pure math/data module: no sockets, process access, DOM, timers or sample data.
 * Manual mode is an approximate pinhole/rig model, NOT a recovered game camera.
 */
(function (root) {
  const MAX_AGE = 1.0, MAX_SKEW = 0.35;
  const finite = x => typeof x === 'number' && Number.isFinite(x);
  const xyz = p => p && ['x', 'y', 'z'].every(k => finite(p[k]));
  const radians = d => d * Math.PI / 180;
  const dot = (a, b) => a.x * b.x + a.y * b.y + a.z * b.z;
  const age = (timestamp, now) => finite(timestamp) && timestamp <= now + MAX_SKEW ? Math.max(0, now - timestamp) : Infinity;
  function validateProfile(raw) {
    const fields = {
      viewport_width: [64, 16384, true], viewport_height: [64, 16384, true],
      vertical_fov_deg: [1, 179, false], eye_height: [-100, 100, false],
      boom_distance: [0, 100, false], shoulder_offset: [-100, 100, false],
      marker_height: [-100, 100, false], roll_deg: [-180, 180, false]
    };
    const profile = {}, issues = [];
    for (const [key, [lo, hi, integer]] of Object.entries(fields)) {
      const input = raw?.[key];
      const value = typeof input === 'string' && input.trim() !== '' ? Number(input) : input;
      if (!finite(value) || value < lo || value > hi || (integer && !Number.isInteger(value))) issues.push(key);
      else profile[key] = value;
    }
    return { ok: issues.length === 0, profile: issues.length ? null : profile, issues };
  }
  function cameras(snapshot) {
    const byStream = new Map();
    const list = [...(Array.isArray(snapshot?.camera_streams) ? snapshot.camera_streams : []), snapshot?.camera];
    for (const cam of list) {
      if (!cam || typeof cam.stream !== 'string' || !cam.stream || !finite(cam.timestamp)) continue;
      const old = byStream.get(cam.stream);
      if (!old || cam.timestamp > old.timestamp) byStream.set(cam.stream, cam);
    }
    return [...byStream.values()];
  }
  function rigPose(local, cam, profile) {
    if (!xyz(local) || !finite(cam?.yaw_deg) || !finite(cam?.pitch_deg)) throw new Error('invalid_pose_input');
    const checked = validateProfile(profile);
    if (!checked.ok) throw new Error('invalid_manual_profile');
    profile = checked.profile;
    const yaw = radians(cam.yaw_deg), pitch = radians(cam.pitch_deg), roll = radians(profile.roll_deg);
    const sy = Math.sin(yaw), cy = Math.cos(yaw), sp = Math.sin(pitch), cp = Math.cos(pitch);
    const forward = { x: sy * cp, y: -sp, z: cy * cp };
    const r0 = { x: cy, y: 0, z: -sy }, u0 = { x: sy * sp, y: cp, z: cy * sp };
    const cr = Math.cos(roll), sr = Math.sin(roll), right = {}, up = {}, origin = {};
    for (const k of ['x', 'y', 'z']) {
      right[k] = r0[k] * cr + u0[k] * sr;
      up[k] = u0[k] * cr - r0[k] * sr;
      origin[k] = local[k] + (k === 'y' ? profile.eye_height : 0) + right[k] * profile.shoulder_offset - forward[k] * profile.boom_distance;
    }
    const width = profile.viewport_width, height = profile.viewport_height;
    const focal = height / (2 * Math.tan(radians(profile.vertical_fov_deg) / 2));
    return { origin, forward, right, up, width, height, focal,
      source: 'manual_calibration', approximate: true, profile_vertical_fov_deg: profile.vertical_fov_deg };
  }
  function projectPoint(point, pose) {
    if (!xyz(point) || !pose || !xyz(pose.origin)) return { visible: false, reason: 'invalid_point' };
    const relative = { x: point.x - pose.origin.x, y: point.y - pose.origin.y, z: point.z - pose.origin.z };
    const depth = dot(relative, pose.forward);
    // A mathematical epsilon, not a claimed game near clip plane.
    if (!finite(depth) || depth <= 0.0001) return { visible: false, reason: 'behind' };
    const x = pose.width / 2 + dot(relative, pose.right) * pose.focal / depth;
    const y = pose.height / 2 - dot(relative, pose.up) * pose.focal / depth;
    if (!finite(x) || !finite(y)) return { visible: false, reason: 'invalid_point' };
    if (x < 0 || x > pose.width || y < 0 || y > pose.height) return { visible: false, reason: 'outside', x, y, depth };
    return { visible: true, x, y, depth };
  }
  function projectSnapshot(snapshot, options, now) {
    const blocked = (reason, extra = {}) => ({ status: 'blocked', reason, points: [], ...extra });
    if (!snapshot || !finite(now)) return blocked('waiting_api');
    if (options?.mode !== 'manual') return blocked('automatic_camera_not_reconstructed');
    if (options.acknowledged !== true) return blocked('manual_approximation_not_acknowledged');
    const check = validateProfile(options.profile);
    if (!check.ok) return blocked('manual_profile_required', { invalid_fields: check.issues });
    const available = cameras(snapshot).filter(c => c.status === 'decoded' && c.source === 'outbound_3_851_field3' && age(c.timestamp, now) <= MAX_AGE);
    let camera;
    if (options.stream) camera = available.find(c => c.stream === options.stream);
    else if (available.length === 1) camera = available[0];
    else if (available.length > 1) return blocked('choose_udp_stream');
    if (!camera) return blocked('fresh_camera_required');
    if (!finite(camera.yaw_deg) || !finite(camera.pitch_deg)) return blocked('camera_angles_missing');
    const entities = Array.isArray(snapshot.entities) ? snapshot.entities : [];
    const locals = entities.filter(e => e?.stream === camera.stream && e.category === 4 && e.is_local === true && xyz(e) && age(e.timestamp, now) <= MAX_AGE);
    if (locals.length !== 1) return blocked(locals.length ? 'ambiguous_local_identity' : 'udp_local_identity_required');
    const local = locals[0];
    if (Math.abs(local.timestamp - camera.timestamp) > MAX_SKEW) return blocked('local_camera_time_skew');
    const profile = check.profile, pose = rigPose(local, camera, profile), points = [], rejected = {};
    const reject = reason => { rejected[reason] = (rejected[reason] || 0) + 1; };
    for (const entity of entities.slice(0, 512)) {
      if (entity?.stream !== camera.stream) { reject('other_stream'); continue; }
      if (entity.category !== 4) { reject('non_actor'); continue; }
      if (entity.is_local === true || (local.key && entity.key === local.key)) { reject('self'); continue; }
      if (!xyz(entity) || age(entity.timestamp, now) > MAX_AGE) { reject('stale_or_invalid'); continue; }
      if (Math.abs(entity.timestamp - camera.timestamp) > MAX_SKEW) { reject('entity_camera_time_skew'); continue; }
      const target = { x: entity.x, y: entity.y + profile.marker_height, z: entity.z };
      const projected = projectPoint(target, pose);
      if (!projected.visible) { reject(projected.reason); continue; }
      const name = typeof entity.name === 'string' && entity.name ? entity.name.replace(/[\u0000-\u001f\u007f]/g, ' ').slice(0, 48) : '实体 ' + String(entity.id1 ?? '?');
      points.push({ x: projected.x, y: projected.y, depth: projected.depth,
        key: entity.key, name, hp: Number.isInteger(entity.hp) ? entity.hp : null,
        distance_world_units: Math.hypot(entity.x - local.x, entity.y - local.y, entity.z - local.z),
        entity_age_seconds: age(entity.timestamp, now) });
    }
    return { status: 'manual_approximate', reason: null, points, rejected, pose, stream: camera.stream,
      profile, camera_age_seconds: age(camera.timestamp, now), local_age_seconds: age(local.timestamp, now),
      angle_source: camera.source, fov_source: 'manual_calibration_not_udp_measurement',
      origin_source: 'udp_local_entity_plus_manual_rig_not_recovered_game_camera',
      unsupported: ['camera_collision', 'client_render_interpolation', 'animation_camera_overrides', 'automatic_mode_detection', 'bones_and_hitboxes', 'android_overlay_host'] };
  }
  const api = Object.freeze({ MAX_AGE, MAX_SKEW, validateProfile, cameras, rigPose, projectPoint, projectSnapshot });
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.UdpProjection = api;
})(typeof globalThis !== 'undefined' ? globalThis : this);