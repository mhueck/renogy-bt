import asyncio
import logging
import sys
from bleak import BleakClient, BleakScanner, BLEDevice

DISCOVERY_TIMEOUT = 5 # max wait time to complete the bluetooth scanning (seconds)
CONNECTION_TIMEOUT = 10 # max wait time for BLE connection (seconds)

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

    async def discover(self):
        mac_address = self.mac_address.upper()
        logging.info("Starting discovery...")
        self.discovered_devices = await BleakScanner.discover(timeout=DISCOVERY_TIMEOUT)
        logging.info("Devices found: %s", len(self.discovered_devices))

        for dev in self.discovered_devices:
            if dev.address != None and (dev.address.upper() == mac_address or (dev.name and dev.name.strip() == self.device_alias)):
                logging.info(f"Found matching device {dev.name} => {dev.address}")
                self.device = dev

    async def connect(self):
        if not self.device:
            self.client = BleakClient(self.mac_address)
        else:
            self.client = BleakClient(self.device)
        try:
            # Add timeout to connection attempt
            await asyncio.wait_for(self.client.connect(), timeout=CONNECTION_TIMEOUT)
            logging.info(f"Client connection: {self.client.is_connected}")
            if not self.client.is_connected: 
                logging.error("Unable to connect")
                self.connect_fail_callback("Connection failed - client not connected")
                return

            for service in self.client.services:
                for characteristic in service.characteristics:
                    if characteristic.uuid == self.notify_char_uuid:
                        await self.client.start_notify(characteristic,  self.notification_callback)
                        logging.info(f"subscribed to notification {characteristic.uuid}")
                    if characteristic.uuid == self.write_char_uuid and service.uuid == self.write_service_uuid:
                        self.write_char_handle = characteristic.handle
                        logging.info(f"found write characteristic {characteristic.uuid}, service {service.uuid}")

        except asyncio.TimeoutError:
            logging.error(f"Connection timeout after {CONNECTION_TIMEOUT} seconds")
            self.connect_fail_callback(f"Connection timeout after {CONNECTION_TIMEOUT} seconds")
        except Exception as e:
            logging.error(f"Error connecting to device: {e}")
            self.connect_fail_callback(f"Connection error: {e}")

    async def notification_callback(self, characteristic, data: bytearray):
        logging.info("notification_callback")
        await self.data_callback(data)

    async def characteristic_write_value(self, data):
        try:
            logging.info(f'writing to {self.write_char_uuid} {data}')
            await self.client.write_gatt_char(self.write_char_handle, bytearray(data), response=False)
            logging.info('characteristic_write_value succeeded')
            await asyncio.sleep(0.5)
        except Exception as e:
            logging.error(f'characteristic_write_value failed {e}')
            raise  # Re-raise exception to propagate error up

    async def characteristic_write_bytes(self, data):
        try:
            logging.info(f'writing to {self.write_char_uuid} {data}')
            await self.client.write_gatt_char(self.write_char_handle, data, response=False)
            logging.info('characteristic_write_value succeeded')
            await asyncio.sleep(0.5)
        except Exception as e:
            logging.error(f'characteristic_write_value failed {e}')
            raise  # Re-raise exception to propagate error up

    async def disconnect(self):
        if self.client and self.client.is_connected:
            logging.info(f"Exit: Disconnecting device: {self.client.name} {self.client.address}")
            await self.client.disconnect()
