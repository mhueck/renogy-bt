import logging
import numbers
import configparser
import os
import sys
import asyncio
import time
from renogybt import EcoWorthyClient, DCChargerClient, BleEspClient, filter_fields

logging.basicConfig(level=logging.INFO)

config_file = sys.argv[1] if len(sys.argv) > 1 else 'config.ini'
config_path = os.path.join(os.path.dirname(os.path.realpath(__file__)), config_file)
config = configparser.ConfigParser(os.environ, inline_comment_prefixes=('#'))
config.read(config_path)


def log_influxdb3(measurement, json_data):
    from influxdb_client_3 import InfluxDBClient3, Point

    host = config['influxdb3']['host']
    token = config['influxdb3'].get('token', None)
    database = config['influxdb3']['database']

    p = Point(measurement)
    for key, value in json_data.items():
        if value is None:
            continue
        if isinstance(value, str):
            p = p.tag(key, value)
        elif isinstance(value, numbers.Number):
            p = p.field(key, value)

    with InfluxDBClient3(host=host, token=token, database=database) as client:
        client.write(record=p)


def process_data(data):
    filtered_data = filter_fields(data, config['data']['fields'])
    logging.info(f" => {filtered_data}")
    if config['influxdb3'].getboolean('enabled'):
        log_influxdb3(filtered_data['__name'], json_data=filtered_data)


async def main():
    gps = BleEspClient(config['gps'])
    charger = DCChargerClient(config['charger'])
    battery = EcoWorthyClient(config['battery'])

    try:
        await asyncio.wait_for(charger.connect(), 35.0)
        await asyncio.wait_for(battery.connect(), 35.0)

        if config['data'].getboolean('enable_polling'):
            last_read = 0
            while True:
                try:
                    await asyncio.wait_for(gps.connect(), 18.0)
                    data = await asyncio.wait_for(gps.read(), 10.0)
                    process_data(data)
                except Exception:
                    pass

                time_ms = int(time.time() * 1000)
                if time_ms - last_read > 57 * 1000:
                    process_data(await asyncio.wait_for(charger.read(), 10.0))
                    process_data(await asyncio.wait_for(battery.read(), 10.0))
                    last_read = time_ms
                await asyncio.sleep(12.0)
        else:
            process_data(await asyncio.wait_for(charger.read(), 30.0))
            process_data(await asyncio.wait_for(battery.read(), 30.0))

    finally:
        await asyncio.wait_for(charger.disconnect(), 5.0)
        await asyncio.wait_for(battery.disconnect(), 5.0)


if __name__ == "__main__":
    asyncio.run(main())
