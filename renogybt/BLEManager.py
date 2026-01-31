import asyncio
import logging
import sys
from bleak import BleakClient, BleakScanner, BLEDevice

DISCOVERY_TIMEOUT = 10 # max wait time to complete the bluetooth scanning (seconds)
CONNECTION_TIMEOUT = 25 # max wait time for BLE connection (seconds)

class BLEManager:
    def __init__(self, mac_address, alias, on_data, on_connect_fail, write_service_uuid, notify_char_uuid, write_char_uuid):
        self.mac_address = mac_address
        self.device_alias = alias
        self.data_callback = on_data
        self.connect_fail_callback = on_connect_fail
        self.write_service_uuid = write_service_uuid
        self.notify_char_uuid = notify_char_uuid
        self.write_char_uuid = write_char_uuid
        self.write_char_handle = None
        self.device: BLEDevice = None
        self.client: BleakClient = None
        self.discovered_devices = []

    async def connect(self):
        try:
            if not self.device:
                logging.info(f"Connecting to: {self.mac_address}")
                self.device = await BleakScanner.find_device_by_address(self.mac_address, timeout=CONNECTION_TIMEOUT)
                if not self.device:
                    raise Exception(f"Cannot find device {self.mac_address}")

            logging.info(f"Found device {self.device}")
            self.client = BleakClient(self.device)
            await self.client.connect(timeout=CONNECTION_TIMEOUT)
            logging.info(f"Client connection: {self.client.is_connected}")
            if not self.client.is_connected: 
                raise Exception(f"Cannot connect to device {self.device}")

            for service in self.client.services:
                for characteristic in service.characteristics:
                    if characteristic.uuid == self.notify_char_uuid:
                        await self.client.start_notify(characteristic,  self.notification_callback)
                        logging.debug(f"subscribed to notification {characteristic.uuid}")
                    if characteristic.uuid == self.write_char_uuid and service.uuid == self.write_service_uuid:
                        self.write_char_handle = characteristic.handle
                        logging.debug(f"found write characteristic {characteristic.uuid}, service {service.uuid}")

        except asyncio.TimeoutError:
            logging.error(f"Connection timeout after {CONNECTION_TIMEOUT} seconds")
            raise
        except Exception as e:
            logging.error(f"Error connecting to device: {e}")
            raise

    async def notification_callback(self, characteristic, data: bytearray):
        logging.debug("notification_callback")
        await self.data_callback(data)

    async def characteristic_write_value(self, data):
        try:
            logging.debug(f'writing to {self.write_char_uuid} {data}')
            await self.client.write_gatt_char(self.write_char_handle, bytearray(data), response=False)
            logging.debug('characteristic_write_value succeeded')
            await asyncio.sleep(0.2)
        except Exception as e:
            logging.error(f'characteristic_write_value failed {e}')
            raise  # Re-raise exception to propagate error up

    async def characteristic_write_bytes(self, data):
        try:
            logging.debug(f'writing to {self.write_char_uuid} {data}')
            await self.client.write_gatt_char(self.write_char_handle, data, response=False)
            logging.debug('characteristic_write_value succeeded')
            await asyncio.sleep(0.2)
        except Exception as e:
            logging.error(f'characteristic_write_value failed {e}')
            raise  # Re-raise exception to propagate error up

    async def disconnect(self):
        if self.client and self.client.is_connected:
            try:
                logging.debug(f"Exit: Disconnecting device: {self.client.name} {self.client.address}")
                await self.client.stop_notify(self.notify_char_uuid)
                await self.client.disconnect()
            except Exception as e:
                logging.warning(f'Error during disconnect {e}')
            