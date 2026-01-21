import json
import logging
import requests
import paho.mqtt.publish as publish
from configparser import ConfigParser
from datetime import datetime
import numbers

PVOUTPUT_URL = 'http://pvoutput.org/service/r2/addstatus.jsp'

class DataLogger:
    def __init__(self, config: ConfigParser):
        self.config = config

    def log_remote(self, json_data):
        headers = { "Authorization" : f"Bearer {self.config['remote_logging']['auth_header']}" }
        req = requests.post(self.config['remote_logging']['url'], json = json_data, timeout=15, headers=headers)
        logging.info("Log remote 200") if req.status_code == 200 else logging.error(f"Log remote error {req.status_code}")

    def log_mqtt(self, json_data):
        logging.info(f"mqtt logging")
        user = self.config['mqtt']['user']
        password = self.config['mqtt']['password']
        auth = None if not user or not password else {"username": user, "password": password}

        publish.single(
            self.config['mqtt']['topic'], payload=json.dumps(json_data),
            hostname=self.config['mqtt']['server'], port=self.config['mqtt'].getint('port'),
            auth=auth, client_id="renogy-bt"
        )

    def log_pvoutput(self, json_data):
        date_time = datetime.now().strftime("d=%Y%m%d&t=%H:%M")
        data = f"{date_time}&v1={json_data['power_generation_today']}&v2={json_data['pv_power']}&v3={json_data['power_consumption_today']}&v4={json_data['load_power']}&v5={json_data['controller_temperature']}&v6={json_data['battery_voltage']}"
        response = requests.post(PVOUTPUT_URL, data=data, headers={
            "Content-Type": "application/x-www-form-urlencoded",
            "X-Pvoutput-Apikey": self.config['pvoutput']['api_key'],
            "X-Pvoutput-SystemId":  self.config['pvoutput']['system_id']
        })
        print(f"pvoutput {response}")

    def log_influxdb2(self, json_data):
        from influxdb_client import InfluxDBClient, Point
        from influxdb_client.client.write_api import SYNCHRONOUS
        
        logging.debug("influxdb2 logging")
        url = self.config['influxdb2']['url']
        token = self.config['influxdb2'].get('token', None)
        org = self.config['influxdb2']['org']
        bucket = self.config['influxdb2']['bucket']

        p = Point(self.config['influxdb2'].get("measurement", "renogy"))
        for key, value in json_data.items():
            if value is None:
                continue
            if isinstance(value, str):
                p = p.tag(key, value)
            elif isinstance(value, numbers.Number):
                p = p.field(key, value)
            else:
                logging.info(f"Ignoring key {key} with unsupported data type. Value: {value}")

        logging.debug(f"Point: {p}")

        client = InfluxDBClient(url=url, token=token, org=org)
        write_api = client.write_api(write_options=SYNCHRONOUS)
        write_api.write(bucket=bucket, record=p)

    def log_influxdb3(self, json_data):
        from influxdb_client_3 import InfluxDBClient3, Point

        logging.debug("influxdb3 logging")
        host = self.config['influxdb3']['host']
        token = self.config['influxdb3'].get('token', None)
        database = self.config['influxdb3']['database']

        p = Point(self.config['influxdb3'].get("measurement", "renogy"))
        for key, value in json_data.items():
            if value is None:
                continue
            if isinstance(value, str):
                p = p.tag(key, value)
            elif isinstance(value, numbers.Number):
                p = p.field(key, value)
            else:
                logging.info(f"Ignoring key {key} with unsupported data type. Value: {value}")

        logging.debug(f"Point: {p}")

        with InfluxDBClient3(host=host, token=token, database=database) as client:
            client.write(record=p)

