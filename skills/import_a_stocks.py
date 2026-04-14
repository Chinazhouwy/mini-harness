#!/usr/bin/env python3
"""
📦 Import A-Share Stocks to ClickHouse - Complete Skill
Version: 2.0.0
"""

import os
import sys
import time
import random
import json
import yaml
import argparse
import logging
import threading
import queue
from datetime import datetime, timedelta
from pathlib import Path
from typing import List, Dict, Optional, Tuple, Any, Union

import pandas as pd
import numpy as np
from loguru import logger
import clickhouse_connect
from mootdx.quotes import Quotes
from mootdx.consts import MARKET_SH, MARKET_SZ, MARKET_BJ

class ClickHouseManager:
    def __init__(self, host='localhost', port=8123, username='default',
                 password='harness123', database='harness_db'):
        self.host = host
        self.port = port
        self.username = username
        self.password = password
        self.database = database
        self.client = None
    
    def connect(self) -> bool:
        try:
            self.client = clickhouse_connect.get_client(
                host=self.host,
                port=self.port,
                username=self.username,
                password=self.password,
                database=self.database
            )
            logger.info(f"Connected to {self.host}:{self.port}/{self.database}")
            return True
        except Exception as e:
            logger.error(f"Failed to connect: {e}")
            return False
    
    def create_table(self, table_name='daily_kline'):
        sql = f"""
        CREATE TABLE IF NOT EXISTS {table_name} (
            trade_date Date,
            stock_code String,
            pre_close Decimal(18,4),
            open Decimal(18,4),
            high Decimal(18,4),
            low Decimal(18,4),
            close Decimal(18,4),
            volume UInt64,
            amount Decimal(18,8),
            turnover_rate Nullable(Decimal(18,4)),
            change Decimal(18,4),
            change_pct Decimal(18,4),
            amplitude Decimal(18,4),
            fetch_time DateTime DEFAULT now()
        ) ENGINE = MergeTree()
        ORDER BY (stock_code, trade_date)
        PARTITION BY toYYYYMM(trade_date)
        TTL trade_date + INTERVAL 10 YEAR
        """
        self.client.command(sql)
        logger.info(f"Table {table_name} created/ensured")
    
    def insert_data(self, df: pd.DataFrame, table_name='daily_kline'):
        """Insert DataFrame into ClickHouse table"""
        try:
            self.client.insert_df(table_name, df)
            logger.info(f"Inserted {len(df)} rows into {table_name}")
            return True
        except Exception as e:
            logger.error(f"Insert failed: {e}")
            return False
    
    def query(self, sql: str):
        return self.client.query(sql)
    
    def close(self):
        if self.client:
            self.client.close()
            self.client = None

class StockDataCollector:
    def __init__(self, config_path: Optional[Path] = None):
        self.config = self.load_config(config_path)
        self.tdx_client = None
        self.ch_manager = None
        self.data_queue = queue.Queue()
        
    def load_config(self, config_path: Optional[Path] = None) -> Dict:
        """Load configuration from file or use defaults"""
        default_config = {
            "clickhouse": {
                "host": "localhost",
                "port": 8123,
                "username": "default",
                "password": "harness123",
                "database": "harness_db"
            },
            "mootdx": {
                "market": "std"
            },
            "stocks": [
                "600406",
                "002463", 
                "300308",
                "601899",
                "600900"
            ],
            "date_range": {
                "start_date": "2025-04-14",
                "end_date": "2026-04-14"
            }
        }
        
        if config_path and config_path.exists():
            with open(config_path, 'r', encoding='utf-8') as f:
                file_config = yaml.safe_load(f)
                # Deep merge configuration
                import copy
                config = copy.deepcopy(default_config)
                for key, value in file_config.items():
                    if key in config and isinstance(config[key], dict) and isinstance(value, dict):
                        config[key].update(value)
                    else:
                        config[key] = value
                return config
        return default_config
    
    def init_clients(self):
        """Initialize TDX and ClickHouse clients"""
        try:
            # Initialize TDX client
            self.tdx_client = Quotes.factory(market=self.config['mootdx']['market'])
            logger.info("TDX client initialized")
            
            # Initialize ClickHouse manager
            ch_config = self.config['clickhouse']
            self.ch_manager = ClickHouseManager(
                host=ch_config['host'],
                port=ch_config['port'],
                username=ch_config['username'],
                password=ch_config.get('password', ''),
                database=ch_config['database']
            )
            return True
        except Exception as e:
            logger.error(f"Failed to initialize clients: {e}")
            return False
    
    def fetch_daily_data(self, stock_code: str, 
                         start_date: str, 
                         end_date: str) -> Optional[pd.DataFrame]:
        """
        Fetch daily data for a single stock.
        """
        max_attempts = 3
        
        for attempt in range(max_attempts):
            try:
                # Fetch daily K-line data
                data = self.tdx_client.bars(
                    symbol=stock_code,
                    frequency=9,  # Daily frequency
                    offset=5000   # Maximum bars to fetch
                )
                
                if data is None or data.empty:
                    logger.warning(f"No data for {stock_code}")
                    return None
                
                # Process data
                df = data.copy()
                
                # Extract date from index
                df['trade_date'] = pd.to_datetime(df.index).date
                df['stock_code'] = stock_code
                df['pre_close'] = df['close'].shift(1).fillna(df['close'])
                df['change'] = df['close'] - df['pre_close']
                df['change_pct'] = (df['change'] / df['pre_close']) * 100
                df['amplitude'] = ((df['high'] - df['low']) / df['low']) * 100
                
                # Reset index
                df = df.reset_index(drop=True)
                
                # Filter by date range
                start_dt = datetime.strptime(start_date, "%Y-%m-%d").date()
                end_dt = datetime.strptime(end_date, "%Y-%m-%d").date()
                
                df = df[(df['trade_date'] >= start_dt) & (df['trade_date'] <= end_dt)]
                
                if df.empty:
                    logger.info(f"No data in range for {stock_code}")
                    return None
                
                # Add metadata columns
                df['turnover_rate'] = None
                df['fetch_time'] = datetime.now()
                
                logger.info(f"Fetched {len(df)} records for {stock_code}")
                return df
                
            except Exception as e:
                logger.warning(f"Attempt {attempt + 1} failed for {stock_code}: {e}")
                if attempt < max_attempts - 1:
                    wait_time = (2 ** attempt) + random.uniform(0.1, 0.5)
                    logger.info(f"Waiting {wait_time:.1f} seconds before retry...")
                    time.sleep(wait_time)
                else:
                    logger.error(f"All attempts failed for {stock_code}")
                continue
        return None
    
    def fetch_all_stocks(self, stock_list: List[str] = None,
                        start_date: Optional[str] = None,
                        end_date: Optional[str] = None) -> List[pd.DataFrame]:
        """Fetch daily data for a list of stocks."""
        stocks_to_fetch = stock_list or self.config.get('stocks', [])
        
        results = []
        for stock_code in stocks_to_fetch:
            logger.info(f"Fetching {stock_code}...")
            
            # Fetch data for this stock
            df = self.fetch_daily_data(
                stock_code=stock_code,
                start_date=start_date or self.config['date_range']['start_date'],
                end_date=end_date or self.config['date_range']['end_date']
            )
            
            if df is not None:
                results.append(df)
                logger.success(f"{stock_code}: {len(df)} records")
            else:
                logger.warning(f"{stock_code}: no data available")
        
        return results
    
    def run(self, args=None):
        parser = argparse.ArgumentParser(description='Import A-share stock data to ClickHouse')
        parser.add_argument('--stocks', type=str, help='Comma-separated stock codes')
        parser.add_argument('--start', type=str, help='Start date (YYYY-MM-DD)')
        parser.add_argument('--end', type=str, help='End date (YYYY-MM-DD)')
        parser.add_argument('--config', type=Path, help='Path to configuration file')
        parser.add_argument('--reset-tables', action='store_true', help='Drop and recreate tables')
        parser.add_argument('--verbose', action='store_true', help='Enable debug logging')
        parser.add_argument('--dry-run', action='store_true', help='Simulate without inserting data')
        
        parsed_args = parser.parse_args(args)
        
        if parsed_args.verbose:
            logger.add(sys.stderr, level="DEBUG")
        else:
            logger.add(sys.stderr, level="INFO")
        
        # Load configuration
        config = self.load_config(parsed_args.config)
        
        # Initialize clients
        self.init_clients()
        
        # Connect to ClickHouse
        if not self.ch_manager.connect():
            sys.exit(1)
        
        # Ensure table exists
        table_name = self.config.get('table_name', 'daily_kline')
        self.ch_manager.create_table(table_name)
        
        # Fetch data for all configured stocks
        start_date = parsed_args.start or config['date_range']['start_date']
        end_date = parsed_args.end or config['date_range']['end_date']
        
        stock_list = parsed_args.stocks
        if stock_list:
            stock_codes = stock_list.split(',')
        else:
            stock_codes = self.config.get('stocks', [])
        
        logger.info(f"Starting import for {len(stock_codes)} stocks from {start_date} to {end_date}")
        
        # Fetch all stock data

        results = self.fetch_all_stocks(stock_codes, start_date, end_date)
        
        logger.info(f"Fetched {len(results)} stocks with data")

        total_records = sum(len(df) for df in results)

        logger.info(f"Total records fetched: {total_records}")

        success = True

        for i, df in enumerate(results, 1):

            logger.info(f"Processing stock {i} of {len(results)} ({len(df)} records)")

            if not parsed_args.dry_run:

                if self.ch_manager.insert_data(df, table_name):

                    logger.success(f"Successfully inserted {len(df)} records")

                else:

                    logger.error(f"Failed to insert records")

                    success = False
            else:
                logger.info(f"Dry run (no data inserted)")

                if df is not None:
                    logger.info(f"Columns in data: {df.columns.tolist()}")
                    logger.info(f"Data shape: {df.shape}")
                    logger.info(f"Sample data:\n{df.head(3)})")
        # Print summary
        logger.info("=" * 60)
        logger.info("DATA IMPORT SUMMARY")
        logger.info(f"Stocks processed: {len(results)}")
        logger.info(f"Total records: {total_records}")
        logger.info(f"Status: {'SUCCESS' if success else 'FAILED'}"
        logger.info("=" * 60)
        return success

if __name__ == "__main__":
    # Get the absolute path to the project root
    project_root = Path(__file__).parent.parent
    
    os.chdir(project_root)
    
    skill_handler = StockDataCollector()
    
    success = skill_handler.run()
    
    sys.exit(0 if success else 1)