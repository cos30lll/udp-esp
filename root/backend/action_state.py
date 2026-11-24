"""Bounded retention of inbound 3/852 actor actions, without guessed semantics.
Exact decimal int64 values avoid JavaScript number precision loss.
Field4 is retained as opaque bytes, never interpreted as text or camera state.
"""
from collections import Counter
from action_semantics import decode_reported_action, SUPPORTED

MAX_VALUES = 128
MAX_PAYLOAD_BYTES = 2048
MAX_BLOCKS = 16
MAX_STATES = 512

def retain_action(entity, action, ordinal):
    values = action['values']
    blocks = action.get('field4_blocks', [])
    retained, remaining = [], MAX_PAYLOAD_BYTES
    for block in blocks[:MAX_BLOCKS]:
        raw = bytes(block)
        take = min(len(raw), remaining)
        retained.append({'byte_length': len(raw), 'hex': raw[:take].hex(),
                         'complete': take == len(raw)})
        remaining -= take
    payload_bytes = sum(len(block) for block in blocks)
    return {
        'id1': entity['id1'], 'id2': str(entity['id2']),
        'category': entity['category'], 'kind': action['kind'],
        'scale': action['scale'], 'frame_action_ordinal': ordinal,
        'values_decimal': [str(v) for v in values[:MAX_VALUES]],
        'value_count': len(values),
        'values_complete': len(values) <= MAX_VALUES,
        'field4_blocks': retained, 'field4_block_count': len(blocks),
        'field4_byte_count': payload_bytes,
        'field4_complete': len(blocks) <= MAX_BLOCKS and payload_bytes <= MAX_PAYLOAD_BYTES,
        'semantic_status': 'raw_action_not_mapped_to_camera',
    }

class ActionStore:
    def __init__(self):
        self.states = {}
        self.counts = Counter()

    def clear(self):
        self.states.clear()
        self.counts.clear()

    def reset_stream(self, stream):
        self.states = {k: v for k, v in self.states.items() if v['stream'] != stream}

    def remove_entity(self, stream, entity_id):
        self.states = {k: v for k, v in self.states.items()
                       if not (v['stream'] == stream and v['id1'] == entity_id)}

    def apply(self, updates, stream, timestamp, packet):
        applied = 0
        for update in updates:
            key = (stream, update['category'], update['id1'], update['id2'], update['kind'])
            previous = self.states.get(key)
            if previous is not None and timestamp < previous['timestamp']:
                self.counts['older_timestamp_ignored'] += 1
                continue
            item = dict(update, stream=stream, timestamp=timestamp, packet=packet,
                        source='inbound_3_852_raw_actor_action')
            semantic = decode_reported_action(item)
            if semantic is not None:
                item['reported_input'] = semantic
                counter = 'semantics_decoded' if semantic['status'] == 'reported_input_not_render_state' else 'semantics_invalid'
                self.counts[counter] += 1
            self.states.pop(key, None)
            self.states[key] = item
            self.counts['updates'] += 1
            if not item['values_complete'] or not item['field4_complete']:
                self.counts['retention_limited'] += 1
            applied += 1
        while len(self.states) > MAX_STATES:
            self.states.pop(next(iter(self.states)))
            self.counts['evicted_at_limit'] += 1
        return applied

    def snapshot(self, identities):
        local = []
        for item in self.states.values():
            identity = identities.get(item['stream'], {})
            entity_id = identity.get('local_entity_id')
            if not entity_id:
                uid = identity.get('local_uid')
                candidates = [int(key) for key, player in identity.get('players', {}).items()
                              if uid and uid != '0' and player.get('uid') == uid]
                entity_id = candidates[0] if len(candidates) == 1 else None
            if item['category'] == 4 and entity_id and item['id1'] == entity_id:
                local.append(dict(item))
        local.sort(key=lambda item: (item['timestamp'], item['frame_action_ordinal']), reverse=True)
        semantic_counts = Counter()
        for item in self.states.values():
            semantic = item.get('reported_input')
            if semantic and semantic['status'] == 'reported_input_not_render_state':
                semantic_counts[semantic['action_name']] += 1
        return {'status': 'reported_inputs_partial_not_camera_state',
                'mapped_action_kinds': sorted(SUPPORTED),
                'semantic_record_counts': dict(semantic_counts),
                'records': len(self.states), 'max_records': MAX_STATES,
                'counts': dict(self.counts), 'local_records': local[:64],
                'local_records_omitted': max(0, len(local) - 64),
                'camera_mapping_complete': False}
