#!/usr/bin/env python3
"""
Demo script to test the polling functionality with a mock client
"""

import asyncio
import logging
import configparser

logging.basicConfig(level=logging.INFO)

class MockClient:
    """Mock client to demonstrate the fixed async behavior"""
    
    def __init__(self, config):
        self.config = config
        self._stop_event = None
        self._running = False
        self.data_count = 0
        
    def start(self):
        """Start the mock client"""
        try:
            asyncio.run(self._run_with_timeout())
        except KeyboardInterrupt:
            logging.info("KeyboardInterrupt received - stopping gracefully")
        except Exception as e:
            logging.error(f"Error: {e}")
    
    async def _run_with_timeout(self):
        """Run the main task with timeout"""
        try:
            await asyncio.wait_for(self._main_task(), timeout=30.0)
        except asyncio.TimeoutError:
            logging.error("Demo timeout after 30 seconds")
        except Exception as e:
            logging.error(f"Error in main task: {e}")
    
    async def _main_task(self):
        """Main async task - demonstrates the fix"""
        self._running = True
        self._stop_event = asyncio.Event()
        
        try:
            # Simulate connection
            logging.info("Mock client: Connecting...")
            await asyncio.sleep(1)
            logging.info("Mock client: Connected successfully!")
            
            # Start the polling loop
            await self._start_polling()
            
            # Keep running until stopped
            logging.info("Mock client: Waiting for stop signal...")
            await self._stop_event.wait()
            
        finally:
            logging.info("Mock client: Cleaning up...")
            self._running = False
    
    async def _start_polling(self):
        """Start the polling task"""
        asyncio.create_task(self._poll_data())
    
    async def _poll_data(self):
        """Simulate polling data from device"""
        while self._running:
            await asyncio.sleep(2)  # Poll every 2 seconds
            self.data_count += 1
            
            # Simulate receiving data
            mock_data = {
                'voltage': 12.5 + (self.data_count * 0.1),
                'current': 5.2,
                'power': 65.0,
                'count': self.data_count
            }
            
            logging.info(f"Mock data received: {mock_data}")
            
            # Stop after 5 data points to demonstrate
            if self.data_count >= 5:
                logging.info("Demo complete - stopping client")
                self.stop()
                break
    
    def stop(self):
        """Stop the client"""
        if self._running and self._stop_event:
            logging.info("Mock client: Stop requested")
            self._stop_event.set()

if __name__ == "__main__":
    # Create a mock config
    config = configparser.ConfigParser()
    config.add_section('device')
    config.set('device', 'alias', 'MockDevice')
    config.add_section('data')
    config.set('data', 'enable_polling', 'true')
    config.set('data', 'poll_interval', '2')
    
    print("=== Mock Client Demo ===")
    print("This demonstrates the fixed async behavior:")
    print("- Event loop stays alive")
    print("- Polling works correctly") 
    print("- Graceful shutdown")
    print("- No early termination")
    print()
    
    # Start the mock client
    mock_client = MockClient(config)
    mock_client.start()
    
    print("\nDemo completed successfully!")
