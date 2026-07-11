import logging
import json
import atexit
import signal
import numbers
import configparser
import os
import sys
import asyncio
import time
import requests
from renogybt import EcoWorthyClient, DCChargerClient, BleEspClient, BLEClient, filter_fields

logging.basicConfig(level=logging.INFO)

# Global GPS coordinates dictionary
gps_coords = {'lat': None, 'lon': None}
coords_path = os.path.join(os.path.dirname(os.path.realpath(__file__)), 'gps_coords.json')


def load_gps_coords():
    if os.path.exists(coords_path):
        try:
            with open(coords_path, 'r') as f:
                saved = json.load(f)
                if isinstance(saved, dict):
                    gps_coords['lat'] = saved.get('lat')
                    gps_coords['lon'] = saved.get('lon')
                    logging.info(f"Loaded GPS coordinates from file system: {gps_coords}")
        except Exception as e:
            logging.error(f"Failed to load GPS coordinates from file system: {e}")


def save_gps_coords():
    try:
        with open(coords_path, 'w') as f:
            json.dump(gps_coords, f)
        logging.info(f"Saved GPS coordinates to file system: {gps_coords}")
    except Exception as e:
        logging.error(f"Failed to save GPS coordinates to file system: {e}")


atexit.register(save_gps_coords)


def handle_sigterm(signum, frame):
    logging.info(f"Received exit signal {signum}, initiating clean shutdown...")
    sys.exit(0)


try:
    signal.signal(signal.SIGTERM, handle_sigterm)
    signal.signal(signal.SIGHUP, handle_sigterm)
except ValueError:
    pass

load_gps_coords()


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


async def weather_poll_loop(ble_client, gps_coords):
    while True:
        try:
            lat = gps_coords.get('lat')
            lon = gps_coords.get('lon')
            if lat is not None and lon is not None:
                logging.info(f"Polling weather for lat={lat}, lon={lon}")
                url = f"https://api.open-meteo.com/v1/forecast?latitude={lat}&longitude={lon}&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum&current=temperature_2m,weather_code&timezone=auto&forecast_days=3&timeformat=unixtime"
                
                response = await asyncio.to_thread(requests.get, url, timeout=15)
                response.raise_for_status()
                weather_json = response.json()
                
                if ble_client.running:
                    ble_client.update_weather(weather_json)
                    logging.info("Weather characteristic updated successfully")
            else:
                logging.warning("Weather polling skipped: GPS location not yet resolved")
        except Exception as e:
            logging.error(f"Error in weather polling loop: {e}")
        
        await asyncio.sleep(60)


async def main():
    gps = BleEspClient(config['gps'])
    charger = DCChargerClient(config['charger'])
    battery = EcoWorthyClient(config['battery'])
    
    ble_client = BLEClient(
        mac_addr=config.get('ble_client', 'mac_addr', fallback=config.get('ble_server', 'mac_addr', fallback=None)),
        name=config.get('ble_client', 'name', fallback=config.get('ble_server', 'name', fallback='SolarBLE'))
    )

    tasks = []

    enable_ble = config.getboolean('ble_client', 'enabled', fallback=config.getboolean('ble_server', 'enabled', fallback=True))

    try:
        logging.info("Connecting to charger...")
        await asyncio.wait_for(charger.connect(), 45.0)
        logging.info("Connecting to battery...")
        await asyncio.wait_for(battery.connect(), 45.0)

        if enable_ble:
            try:
                await ble_client.start()
            except Exception as e:
                logging.error(f"Failed to start BLE client: {e}. Continuing without BLE client functionality.")
        else:
            logging.info("BLE client is disabled in config.")

        if config['data'].getboolean('enable_polling'):
            async def gps_loop():
                while True:
                    start_time = asyncio.get_event_loop().time()
                    try:
                        await asyncio.wait_for(gps.connect(), 15.0)
                        data = await asyncio.wait_for(gps.read(), 10.0)
                        if data.get('lat') is not None and data.get('lon') is not None:
                            gps_coords['lat'] = data['lat']
                            gps_coords['lon'] = data['lon']
                        process_data(data)
                    except Exception as gps_err:
                        logging.debug(f"GPS connection/read skipped: {gps_err}")
                        try:
                            await gps.disconnect()
                        except Exception:
                            pass
                        await asyncio.sleep(45.0)
                    
                    elapsed = asyncio.get_event_loop().time() - start_time
                    sleep_time = max(0.1, 12.0 - elapsed)
                    await asyncio.sleep(sleep_time)

            async def charger_battery_loop():
                while True:
                    start_time = asyncio.get_event_loop().time()
                    try:
                        logging.info("Reading charger data...")
                        charger_data = await asyncio.wait_for(charger.read(), 20.0)
                        logging.info("Reading battery data...")
                        battery_data = await asyncio.wait_for(battery.read(), 20.0)
                        process_data(charger_data)
                        process_data(battery_data)

                        if ble_client.running:
                            ble_client.update_battery(
                                percentage=battery_data.get('percentage', 0),
                                power=battery_data.get('power', 0),
                                voltage=battery_data.get('voltage', 0),
                                temperature=battery_data.get('temperature', 0),
                            )
                            ble_client.update_charger(
                                pv_voltage=charger_data.get('pv_voltage', 0),
                                pv_current=charger_data.get('pv_current', 0),
                                alternator_voltage=charger_data.get('alternator_voltage', 0),
                                alternator_current=charger_data.get('alternator_current', 0),
                            )
                    except Exception as err:
                        logging.critical(f"FATAL: Error reading from charger/battery: {err}. Exiting application.")
                        raise err
                    
                    elapsed = asyncio.get_event_loop().time() - start_time
                    sleep_time = max(0.1, 60.0 - elapsed)
                    await asyncio.sleep(sleep_time)

            tasks.append(asyncio.create_task(weather_poll_loop(ble_client, gps_coords)))
            tasks.append(asyncio.create_task(gps_loop()))
            tasks.append(asyncio.create_task(charger_battery_loop()))

            await asyncio.gather(*tasks)
        else:
            charger_data = await asyncio.wait_for(charger.read(), 30.0)
            battery_data = await asyncio.wait_for(battery.read(), 30.0)
            process_data(charger_data)
            process_data(battery_data)
            if ble_client.running:
                ble_client.update_battery(
                    percentage=battery_data.get('percentage', 0),
                    power=battery_data.get('power', 0),
                    voltage=battery_data.get('voltage', 0),
                    temperature=battery_data.get('temperature', 0),
                )
                ble_client.update_charger(
                    pv_voltage=charger_data.get('pv_voltage', 0),
                    pv_current=charger_data.get('pv_current', 0),
                    alternator_voltage=charger_data.get('alternator_voltage', 0),
                    alternator_current=charger_data.get('alternator_current', 0),
                )

    finally:
        for t in tasks:
            t.cancel()
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)
        await ble_client.stop()
        try:
            await asyncio.wait_for(charger.disconnect(), 5.0)
        except Exception:
            pass
        try:
            await asyncio.wait_for(battery.disconnect(), 5.0)
        except Exception:
            pass


if __name__ == "__main__":
    asyncio.run(main())
