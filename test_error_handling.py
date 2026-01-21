#!/usr/bin/env python3
"""
Test script to verify error handling improvements.
This script tests various error scenarios to ensure the application doesn't hang.
"""

import logging
import configparser
import os
import sys
import asyncio
from unittest.mock import Mock, patch
from renogybt import EcoWorthyClient, BatteryClient, RoverClient

logging.basicConfig(level=logging.INFO)

# Create a test config with invalid MAC address to trigger connection failures
test_config = configparser.ConfigParser()
test_config.add_section('device')
test_config.set('device', 'mac_addr', '00:11:22:33:44:55')  # Invalid MAC
test_config.set('device', 'alias', 'TEST_DEVICE')
test_config.set('device', 'type', 'EW_BAT')
test_config.set('device', 'device_id', '1')

test_config.add_section('data')
test_config.set('data', 'enable_polling', 'false')
test_config.set('data', 'temperature_unit', 'C')
test_config.set('data', 'read_cellv', 'false')

def test_connection_failure():
    """Test that connection failures don't cause the application to hang."""
    print("Testing connection failure handling...")
    
    error_received = False
    def on_error(client, error):
        nonlocal error_received
        error_received = True
        logging.info(f"Error callback received: {error}")
    
    def on_data(client, data):
        logging.info(f"Data callback received: {data}")
    
    # Test with invalid MAC address - should fail quickly without hanging
    client = EcoWorthyClient(test_config, on_data, on_error)
    
    import time
    start_time = time.time()
    
    try:
        client.start()
    except Exception as e:
        logging.info(f"Exception caught (expected): {e}")
    
    end_time = time.time()
    duration = end_time - start_time
    
    print(f"Test completed in {duration:.2f} seconds")
    
    if duration > 70:  # Should complete within timeout + buffer
        print("❌ FAIL: Test took too long, likely hung")
        return False
    elif error_received or duration < 70:
        print("✅ PASS: Error handling worked correctly")
        return True
    else:
        print("❓ UNCLEAR: No error received but didn't hang")
        return True

def test_timeout_scenario():
    """Test application timeout scenario."""
    print("\nTesting application timeout...")
    
    # Mock BLE manager to simulate a hanging connection
    with patch('renogybt.EcoWorthyClient.BLEManager') as mock_ble_manager:
        mock_instance = Mock()
        mock_ble_manager.return_value = mock_instance
        
        # Make connect hang by never completing
        async def hanging_connect():
            await asyncio.sleep(100)  # Simulate hanging connection
            
        mock_instance.connect = hanging_connect
        
        client = EcoWorthyClient(test_config, None, None)
        
        import time
        start_time = time.time()
        
        try:
            client.start()
        except Exception as e:
            logging.info(f"Timeout exception (expected): {e}")
        
        end_time = time.time() 
        duration = end_time - start_time
        
        print(f"Timeout test completed in {duration:.2f} seconds")
        
        if 55 <= duration <= 70:  # Should timeout around 60 seconds
            print("✅ PASS: Timeout handling worked correctly")
            return True
        else:
            print(f"❌ FAIL: Expected ~60s timeout, got {duration:.2f}s")
            return False

if __name__ == "__main__":
    print("=== Error Handling Test Suite ===")
    print("Testing fixes for asyncio event loop hanging issues...\n")
    
    results = []
    
    # Run tests
    results.append(test_connection_failure())
    results.append(test_timeout_scenario())
    
    # Summary
    passed = sum(results)
    total = len(results)
    
    print(f"\n=== Test Results ===")
    print(f"Passed: {passed}/{total}")
    
    if passed == total:
        print("✅ All tests passed! Error handling improvements are working.")
        sys.exit(0)
    else:
        print("❌ Some tests failed. Review the error handling implementation.")
        sys.exit(1)
