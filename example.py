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

# the callback func when you receive data
def on_data_received(client, data):
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
    devices = {}
    gps_device = None
    for i in range(1, 6):
        if config.has_section(f"device{i}"):
            sec = config[f"device{i}"]
        else:
            break
        # start client
        if sec['type'] == 'RNG_DCC':
            devices[f"device{i}"] = {"config": sec}
            devices[f"device{i}"]["client"] = DCChargerClient(sec, on_data_received, on_error)
        elif sec['type'] == 'EW_BAT':
            devices[f"device{i}"] = {"config": sec}
            devices[f"device{i}"]["client"] = EcoWorthyClient(sec, on_data_received, on_error)
        elif sec['type'] == 'BLE_ESP':
            gps_device = BleEspClient(sec, on_data_received, on_error)
        else:
            logging.error("unknown device type")

    try:
        # regular devices are connected directly, they are expected to always be present.
        for device in devices:
            await asyncio.wait_for(devices[device]["client"].connect(), 35.0)

        if config['data'].getboolean('enable_polling'):
            last_read = 0
            while True:
                # The GPS device is only available when it is powered on, so we try to connect and read it every time. If it is not available, we just log the error and continue with the other devices.
                try:
                    await asyncio.wait_for(gps_device.connect(), 18.0)
                    await asyncio.wait_for(gps_device.read(), 10.0)
                except Exception as e:
                    pass

                time_ms = int(time.time() * 1000)
                if time_ms - last_read > 57 * 1000:
                    for device in devices:
                        await asyncio.wait_for(devices[device]["client"].read(), 10.0)
                    last_read = time_ms
                await asyncio.sleep(12.0)
        else:
            for device in devices:
                await asyncio.wait_for(devices[device]["client"].read(), 30.0)

    finally:
        for device in devices:
            await asyncio.wait_for(devices[device]["client"].disconnect(), 5.0)

if __name__ == "__main__":
    asyncio.run(main(config)) # Launch the event loop and execute main()
