import logging

from .RenogyClient import RenogyClient
from .Utils import bytes_to_int, parse_temperature

FUNCTION = {
    3: "READ",
    6: "WRITE"
}

CHARGING_STATE = {
    0: 'deactivated',
    1: 'activated',
    2: 'mppt',
    3: 'equalizing',
    4: 'boost',
    5: 'floating',
    6: 'current limiting',
    8: 'alternator direct'
}


class DCChargerClient(RenogyClient):
    def __init__(self, config):
        super().__init__(config)
        self.sections = [
            {'register': 256, 'words': 30, 'parser': self.parse_charging_info},
        ]

    def parse_charging_info(self, bs):
        data = {}
        data['function'] = FUNCTION.get(bytes_to_int(bs, 1, 1))
        data['battery_percentage'] = bytes_to_int(bs, 3, 2)
        data['battery_voltage'] = bytes_to_int(bs, 5, 2, scale=0.1)
        data['combined_charge_current'] = bytes_to_int(bs, 7, 2, scale=0.01)
        data['controller_temperature'] = parse_temperature(bytes_to_int(bs, 9, 1), "C")
        data['battery_temperature'] = parse_temperature(bytes_to_int(bs, 10, 1), "C")
        data['alternator_voltage'] = bytes_to_int(bs, 11, 2, scale=0.1)
        data['alternator_current'] = bytes_to_int(bs, 13, 2, scale=0.01)
        data['alternator_power'] = bytes_to_int(bs, 15, 2)
        data['pv_voltage'] = bytes_to_int(bs, 17, 2, scale=0.1)
        data['pv_current'] = bytes_to_int(bs, 19, 2, scale=0.01)
        data['pv_power'] = bytes_to_int(bs, 21, 2)
        data['battery_min_voltage_today'] = bytes_to_int(bs, 25, 2, scale=0.1)
        data['battery_max_voltage_today'] = bytes_to_int(bs, 27, 2, scale=0.1)
        data['battery_max_current_today'] = bytes_to_int(bs, 29, 2, scale=0.01)
        data['max_charging_power_today'] = bytes_to_int(bs, 33, 2)
        data['charging_amp_hours_today'] = bytes_to_int(bs, 37, 2)
        data['power_generation_today'] = bytes_to_int(bs, 41, 2)
        data['total_working_days'] = bytes_to_int(bs, 45, 2)
        data['count_battery_overdischarged'] = bytes_to_int(bs, 47, 2)
        data['count_battery_fully_charged'] = bytes_to_int(bs, 49, 2)
        data['battery_ah_total_accumulated'] = bytes_to_int(bs, 51, 4)
        data['power_generation_total'] = bytes_to_int(bs, 59, 4)
        self.data.update(data)
