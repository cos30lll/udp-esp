"""UDP camera inputs, not a reconstructed render camera.
Evidence: login parser 060110F7 (fields 16/19/51),
APOBCOHEIOP inbound 3/2055 (perspective=1, UID=2), handler 06020412.
Never infer a view from an absent login dictionary, a request, or a base FOV.
"""

from identity import fields, IdentityError
from camera_rules import base_rule, required_inputs


def _u32(value):
    if value > 0xffffffff:
        raise IdentityError('camera context uint32 overflow')
    return value


def _perspective_entry(data):
    uid, perspective = 0, 0
    value_present = False
    for number, wire, value in fields(data):
        if number == 1 and wire == 0:
            uid = value
        elif number == 2 and wire == 0:
            perspective = _u32(value)
            value_present = True
    return {'uid': str(uid), 'perspective': perspective,
            'perspective_field_present': value_present}


def decode_perspective_request(data):
    # HGJGEEDNEMF: field1 is the requested perspective; omitted scalar is First.
    perspective = 0
    present = False
    for number, wire, value in fields(data):
        if number != 1 or wire != 0:
            raise IdentityError('unverified perspective request field')
        perspective = _u32(value)
        present = True
    if perspective not in (0, 1):
        raise IdentityError('unsupported requested perspective')
    return {'command':2055, 'reset':False, 'request':True,
            'perspective':perspective, 'perspective_field_present':present}

def decode_context(command, data):
    if command not in (1, 2055):
        return None
    out = {'command': command, 'reset': command == 1}
    if command == 1:
        out.update(gameplay_type=None, gameplay_id=None, perspectives=[])
        for number, wire, value in fields(data):
            if number in (16, 19) and wire == 0:
                out['gameplay_type' if number == 16 else 'gameplay_id'] = _u32(value)
            elif number == 51 and wire == 2:
                if len(out['perspectives']) >= 2048:
                    raise IdentityError('camera perspective map limit')
                out['perspectives'].append(_perspective_entry(value))
    else:
        # In this confirmed message schema, an omitted uint32 is zero (First).
        # A nonzero UID is still mandatory before associating it with anyone.
        out.update(uid=None, perspective=0, perspective_field_present=False)
        for number, wire, value in fields(data):
            if number == 1 and wire == 0:
                out['perspective'] = _u32(value)
                out['perspective_field_present'] = True
            elif number == 2 and wire == 0:
                out['uid'] = str(value)
    return out


class ContextStore:
    def __init__(self):
        self.streams = {}

    def clear(self):
        self.streams.clear()

    def apply(self, event, stream, timestamp, packet):
        previous = self.streams.get(stream)
        if previous is not None and timestamp < previous['timestamp']:
            return False
        if event['reset'] or previous is None:
            state = {'stream': stream, 'gameplay_type': None, 'gameplay_id': None,
                     'login_timestamp': None, 'login_packet': None,
                     'initial_dictionary_entries': None, 'perspectives': {}}
            self.streams[stream] = state
        else:
            state = previous
        state.update(timestamp=timestamp, packet=packet)
        if event.get('request') is True:
            state['requested_perspective'] = {
                'value':event['perspective'], 'timestamp':timestamp, 'packet':packet,
                'source':'outbound_3_2055_request_not_confirmed',
                'confirmed':False}
            entries = []
            source = 'outbound_3_2055_request_not_confirmed'
        elif event['command'] == 1:
            state.update(gameplay_type=event['gameplay_type'],
                         gameplay_id=event['gameplay_id'],
                         login_timestamp=timestamp, login_packet=packet,
                         initial_dictionary_entries=len(event['perspectives']))
            entries = event['perspectives']
            source = 'inbound_3_1_field51'
        else:
            entries = [event]
            source = 'inbound_3_2055'
        for entry in entries:
            uid = entry.get('uid')
            if not uid or uid == '0':
                continue
            old = state['perspectives'].get(uid)
            if old is not None and timestamp < old['timestamp']:
                continue
            state['perspectives'][uid] = {
                'uid': uid, 'value': entry['perspective'],
                'perspective_field_present': entry['perspective_field_present'],
                'source': source, 'timestamp': timestamp, 'packet': packet}
        if len(state['perspectives']) > 2048:
            keep = sorted(state['perspectives'],
                          key=lambda uid: state['perspectives'][uid]['timestamp'],
                          reverse=True)[:2048]
            state['perspectives'] = {uid: state['perspectives'][uid] for uid in keep}
        if len(self.streams) > 32:
            keep = sorted(self.streams, key=lambda key: self.streams[key]['timestamp'],
                          reverse=True)[:32]
            self.streams = {key: self.streams[key] for key in keep}
        return True

    def snapshot(self, identities):
        result = []
        for stream, state in self.streams.items():
            uid = identities.get(stream, {}).get('local_uid')
            view = state['perspectives'].get(uid) if uid and uid != '0' else None
            valid = view is not None and view['value'] in (0, 1)
            item = {key: state[key] for key in (
                'stream', 'timestamp', 'packet', 'gameplay_type', 'gameplay_id',
                'login_timestamp', 'login_packet', 'initial_dictionary_entries')}
            item.update(
                requested_perspective=dict(state['requested_perspective']) if state.get('requested_perspective') else None,
                gameplay_name='UGC2' if state['gameplay_type'] == 12 else None,
                local_uid=uid, reported_perspective=dict(view) if view else None,
                reported_perspective_name=('First' if view['value'] == 0 else 'Third')
                    if valid else None,
                perspective_status='reported_udp_not_render_verified' if valid
                    else 'unsupported_perspective' if view else 'not_observed_for_local_uid',
                render_camera_status='not_reconstructed',
                camera_origin=None, fov_deg=None,
                source='passive_udp_context',
                missing_inputs=['final_camera_origin', 'final_vertical_fov',
                                'render_state_and_local_modifiers'])
            rule = base_rule(state['gameplay_type'], state['gameplay_id'])
            if rule is not None:
                item['static_camera_rule'] = rule
                item['missing_inputs'] = required_inputs(view['value'] if valid else None)
                item['rule_status'] = 'equations_recovered_dynamic_inputs_incomplete'
            result.append(item)
        return result
