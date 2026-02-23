import logging
import configparser
import os
import sys
import asyncio
import time
from renogybt import EcoWorthyClient, DCChargerClient, DataLogger, Utils, BleEspClient

logging.basicConfig(level=logging.INFO)

config_file = sys.argv[1] if len(sys.argv) > 1 else 'config.ini'
config_path = os.path.join(os.path.dirname(os.path.realpath(__file__)), config_file)
config = configparser.ConfigParser(os.environ, inline_comment_prefixes=('#'))
config.read(config_path)
data_logger: DataLogger = DataLogger(config)

engine_running = False

# the callback func when you receive data
def on_data_received(client, data):
    global engine_running
    if "alternator_voltage" in data:
        if data["alternator_voltage"] > 13.5:
            engine_running = True
        else:
            engine_running = False
    filtered_data = Utils.filter_fields(data, config['data']['fields'])
    logging.info(f" => {filtered_data}")
    if config['remote_logging'].getboolean('enabled'):
        data_logger.log_remote(json_data=filtered_data)
    if config['mqtt'].getboolean('enabled'):
        data_logger.log_mqtt(json_data=filtered_data)
    if config['influxdb2'].getboolean('enabled'):
        data_logger.log_influxdb2(json_data=filtered_data)
    if config['influxdb3'].getboolean('enabled'):
        data_logger.log_influxdb3(client.config['type'], json_data=filtered_data)

# error callback
def on_error(client, error):
    logging.error(f"on_error: {error}")

async def main(config):
    global engine_running
    devices = {}
    for i in range(1, 6):
        if config.has_section(f"device{i}"):
            sec = config[f"device{i}"]
        else:
            break
        devices[f"device{i}"] = {"config": sec}
        # start client
        if sec['type'] == 'RNG_DCC':
            devices[f"device{i}"]["client"] = DCChargerClient(sec, on_data_received, on_error)
        elif sec['type'] == 'EW_BAT':
            devices[f"device{i}"]["client"] = EcoWorthyClient(sec, on_data_received, on_error)
        elif sec['type'] == 'BLE_ESP':
            devices[f"device{i}"]["client"] = BleEspClient(sec, on_data_received, on_error)
        else:
            logging.error("unknown device type")

    try:
        for device in devices:
            await asyncio.wait_for(devices[device]["client"].connect(), 35.0)

        if config['data'].getboolean('enable_polling'):
            interval = config['data'].getint('poll_interval')
            poll_counter = 0
            while True:
                start_time_ms = int(time.time() * 1000)
                for device in devices:
                    poll_modulo = devices[device]["config"].getint("poll_modulo", 1)
                    if devices[device]["config"]['type'] == 'BLE_ESP':
                        if engine_running:
                            poll_modulo = 1
                        else:
                            poll_modulo = 30
                    if (poll_counter % poll_modulo) == 0:
                        await asyncio.wait_for(devices[device]["client"].read(), 30.0)
                current_time_ms = int(time.time() * 1000)
                wait_time_ms = max(1000, interval * 1000 + start_time_ms - current_time_ms)
                logging.info(f"Waiting for {wait_time_ms/1000} s")
                await asyncio.sleep(wait_time_ms/1000.0)
                poll_counter += 1
        else:
            for device in devices:
                await asyncio.wait_for(devices[device]["client"].read(), 30.0)

    finally:
        for device in devices:
            await asyncio.wait_for(devices[device]["client"].disconnect(), 5.0)

if __name__ == "__main__":
    asyncio.run(main(config)) # Launch the event loop and execute main()
