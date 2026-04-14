#!/usr/bin/env python3
"""
📦 A-Share Stock Data Import Skill for ClickHouse
Version: 3.0.0 - Production Ready
"""

import sys
import time
import random
from datetime import datetime, timedelta
from pathlib import Path
from typing import List, Optional

import pandas as pd
import clickhouse_connect
from mootdx.quotes import Quotes


def import_stocks_to_clickhouse(
    stock_codes: List[str],
    start_date: str = None,
    end_date: str = None,
    clickhouse_host: str = "localhost",
    clickhouse_port: int = 8123,
    clickhouse_user: str = "default",
    clickhouse_password: str = "harness123",
    clickhouse_database: str = "harness_db",
    table_name: str = "daily_kline",
    drop_table: bool = False,
    verbose: bool = False,
) -> dict:
    """
    Import A-share stock data to ClickHouse.

    Args:
        stock_codes: List of stock codes (e.g., ['600406', '600900'])
        start_date: Start date in YYYY-MM-DD format
        end_date: End date in YYYY-MM-DD format
        clickhouse_host: ClickHouse server host
        clickhouse_port: ClickHouse server port
        clickhouse_user: ClickHouse username
        clickhouse_password: ClickHouse password
        clickhouse_database: ClickHouse database name
        table_name: Table name to store data
        drop_table: Drop existing table before import
        verbose: Enable verbose logging

    Returns:
        Dictionary with import statistics
    """

    # Setup logging
    import logging

    logging.basicConfig(
        level=logging.DEBUG if verbose else logging.INFO,
        format="%(asctime)s - %(levelname)s - %(message)s",
    )
    logger = logging.getLogger(__name__)

    # Default date range: last 1 year
    if not end_date:
        end_date = datetime.now().strftime("%Y-%m-%d")
    if not start_date:
        start_date = (
            datetime.strptime(end_date, "%Y-%m-%d") - timedelta(days=365)
        ).strftime("%Y-%m-%d")

    logger.info(f"Importing {len(stock_codes)} stocks from {start_date} to {end_date}")

    # Connect to ClickHouse
    try:
        ch_client = clickhouse_connect.get_client(
            host=clickhouse_host,
            port=clickhouse_port,
            username=clickhouse_user,
            password=clickhouse_password,
            database=clickhouse_database,
        )
        logger.info(f"Connected to ClickHouse at {clickhouse_host}:{clickhouse_port}")
    except Exception as e:
        logger.error(f"Failed to connect to ClickHouse: {e}")
        return {"success": False, "error": str(e)}

    # Create table with correct schema
    try:
        if drop_table:
            ch_client.command(f"DROP TABLE IF EXISTS {table_name}")
            logger.info(f"Dropped table {table_name}")

        create_table_sql = f"""
        CREATE TABLE IF NOT EXISTS {table_name} (
            trade_date Date,
            stock_code String,
            pre_close Decimal64(4),
            open Decimal64(4),
            high Decimal64(4),
            low Decimal64(4),
            close Decimal64(4),
            volume UInt64,
            amount Decimal64(8),
            turnover_rate Nullable(Decimal64(4)),
            change Decimal64(4),
            change_pct Decimal64(4),
            amplitude Decimal64(4),
            fetch_time DateTime DEFAULT now()
        )
        ENGINE = MergeTree()
        ORDER BY (stock_code, trade_date)
        PARTITION BY toYYYYMM(trade_date)
        TTL trade_date + INTERVAL 10 YEAR
        """
        ch_client.command(create_table_sql)
        logger.info(f"Table {table_name} created/ensured")
    except Exception as e:
        logger.error(f"Failed to create table: {e}")
        ch_client.close()
        return {"success": False, "error": str(e)}

    # Initialize TDX client
    try:
        tdx_client = Quotes.factory(market="std")
        logger.info("TDX client initialized")
    except Exception as e:
        logger.error(f"Failed to initialize TDX client: {e}")
        ch_client.close()
        return {"success": False, "error": str(e)}

    # Import statistics
    stats = {
        "total_stocks": len(stock_codes),
        "successful_stocks": 0,
        "failed_stocks": 0,
        "total_records": 0,
        "start_date": start_date,
        "end_date": end_date,
    }

    # Import each stock
    for stock_code in stock_codes:
        logger.info(f"Processing {stock_code}...")

        for attempt in range(3):  # Retry up to 3 times
            try:
                # Fetch data from mootdx
                kline_data = tdx_client.bars(
                    symbol=stock_code, frequency=9, offset=5000
                )

                if kline_data is None or kline_data.empty:
                    logger.warning(f"No data for {stock_code}")
                    stats["failed_stocks"] += 1
                    break

                # Process the data
                df = kline_data.copy()

                # Add stock code
                df["stock_code"] = stock_code

                # Convert index to date
                df["trade_date"] = pd.to_datetime(df.index).date

                # Reset index
                df = df.reset_index(drop=True)

                # Filter by date range
                start_dt = datetime.strptime(start_date, "%Y-%m-%d").date()
                end_dt = datetime.strptime(end_date, "%Y-%m-%d").date()
                df = df[(df["trade_date"] >= start_dt) & (df["trade_date"] <= end_dt)]

                if df.empty:
                    logger.warning(f"No data in range for {stock_code}")
                    stats["failed_stocks"] += 1
                    break

                # Calculate required fields
                df["pre_close"] = df["close"].shift(1).fillna(df["close"])
                df["change"] = df["close"] - df["pre_close"]
                df["change_pct"] = (df["change"] / df["pre_close"]) * 100
                df["amplitude"] = ((df["high"] - df["low"]) / df["low"]) * 100
                df["turnover_rate"] = None

                # Ensure column order
                final_columns = [
                    "trade_date",
                    "stock_code",
                    "pre_close",
                    "open",
                    "high",
                    "low",
                    "close",
                    "volume",
                    "amount",
                    "turnover_rate",
                    "change",
                    "change_pct",
                    "amplitude",
                ]

                # Create final DataFrame
                final_df = pd.DataFrame()
                for col in final_columns:
                    if col in df.columns:
                        final_df[col] = df[col]

                # Insert into ClickHouse
                ch_client.insert_df(table_name, final_df)

                # Update statistics
                stats["successful_stocks"] += 1
                stats["total_records"] += len(final_df)
                logger.info(
                    f"Successfully imported {len(final_df)} records for {stock_code}"
                )
                break  # Success, exit retry loop

            except Exception as e:
                logger.warning(f"Attempt {attempt + 1} failed for {stock_code}: {e}")
                if attempt < 2:  # Not the last attempt
                    wait_time = (2**attempt) + random.uniform(0.1, 0.5)
                    logger.info(f"Waiting {wait_time:.1f} seconds before retry...")
                    time.sleep(wait_time)
                else:
                    logger.error(f"All attempts failed for {stock_code}")
                    stats["failed_stocks"] += 1
                continue

    # Close connections
    ch_client.close()

    # Calculate success rate
    success_rate = (
        stats["successful_stocks"] / stats["total_stocks"] * 100
        if stats["total_stocks"] > 0
        else 0
    )
    stats["success_rate"] = f"{success_rate:.1f}%"
    stats["success"] = stats["failed_stocks"] == 0

    # Print summary
    logger.info("=" * 60)
    logger.info("IMPORT COMPLETE")
    logger.info(
        f"Stocks: {stats['successful_stocks']}/{stats['total_stocks']} successful"
    )
    logger.info(f"Records: {stats['total_records']}")
    logger.info(f"Date range: {start_date} to {end_date}")
    logger.info(f"Success rate: {stats['success_rate']}")
    logger.info("=" * 60)

    return stats


def main():
    """Command-line interface for the skill."""
    import argparse

    parser = argparse.ArgumentParser(description="Import A-share stocks to ClickHouse")
    parser.add_argument(
        "--stocks",
        required=True,
        help="Comma-separated stock codes (e.g., '600406,600900')",
    )
    parser.add_argument("--start", help="Start date (YYYY-MM-DD), default: 1 year ago")
    parser.add_argument("--end", help="End date (YYYY-MM-DD), default: today")
    parser.add_argument("--host", default="localhost", help="ClickHouse host")
    parser.add_argument("--port", type=int, default=8123, help="ClickHouse port")
    parser.add_argument("--user", default="default", help="ClickHouse username")
    parser.add_argument("--password", default="harness123", help="ClickHouse password")
    parser.add_argument("--database", default="harness_db", help="ClickHouse database")
    parser.add_argument("--table", default="daily_kline", help="Table name")
    parser.add_argument(
        "--drop-table", action="store_true", help="Drop table before import"
    )
    parser.add_argument("--verbose", action="store_true", help="Enable verbose logging")

    args = parser.parse_args()

    # Parse stock codes
    stock_codes = [code.strip() for code in args.stocks.split(",")]

    # Run import
    result = import_stocks_to_clickhouse(
        stock_codes=stock_codes,
        start_date=args.start,
        end_date=args.end,
        clickhouse_host=args.host,
        clickhouse_port=args.port,
        clickhouse_user=args.user,
        clickhouse_password=args.password,
        clickhouse_database=args.database,
        table_name=args.table,
        drop_table=args.drop_table,
        verbose=args.verbose,
    )

    # Exit with appropriate code
    sys.exit(0 if result.get("success", False) else 1)


if __name__ == "__main__":
    main()
