#!/usr/bin/env python3
"""
Import stock data to ClickHouse from tdx with proper timezone handling
"""

import os
import sys
import time
import traceback
from datetime import datetime, timedelta
from decimal import Decimal
from typing import List, Dict, Optional

import clickhouse_connect
import numpy as np
import pandas as pd
from loguru import logger
from mootdx.quotes import Quotes

pd.set_option("future.no_silent_downcasting", True)


def connect_clickhouse():
    """Connect to ClickHouse server using docker-compose credentials"""
    try:
        client = clickhouse_connect.get_client(
            host="localhost",
            port=8123,
            username="default",
            password="harness123",
            database="harness_db",
            compress=False,
            settings={
                "allow_experimental_object_type": 1,
                "async_insert": 1,
                "wait_for_async_insert": 1,
            },
        )
        # Verify connection
        client.command("SELECT 1")
        logger.info("Connected to ClickHouse harness_db successfully")
        return client
    except Exception as e:
        logger.error(f"Failed to connect to ClickHouse: {e}")
        raise


def reset_daily_kline_table(client):
    """Drop and recreate daily_kline table with schema from skill document"""
    drop_sql = "DROP TABLE IF EXISTS daily_kline"
    client.command(drop_sql)
    logger.info("Dropped daily_kline table")

    create_sql = """
    CREATE TABLE IF NOT EXISTS daily_kline (
        trade_date Date COMMENT '交易日期',
        stock_code String COMMENT '股票代码',
        pre_close Decimal64(4) COMMENT '前收盘价 元',
        open Decimal64(4) COMMENT '开盘价',
        high Decimal64(4) COMMENT '最高价',
        low Decimal64(4) COMMENT '最低价',
        close Decimal64(4) COMMENT '收盘价',
        volume UInt64 COMMENT '成交量 股',
        amount Decimal64(8) COMMENT '成交金额 万元',
        turnover_rate Nullable(Decimal64(4)) COMMENT '换手率 %',
        change Decimal64(4) COMMENT '涨跌额 元',
        change_pct Decimal64(4) COMMENT '涨跌幅 %',
        amplitude Decimal64(4) COMMENT '振幅 %',
        fetch_time DateTime DEFAULT now() COMMENT '采集时间'
    ) ENGINE = MergeTree()
    ORDER BY (stock_code, trade_date)
    PARTITION BY toYYYYMM(trade_date)
    TTL trade_date + INTERVAL 10 YEAR
    """
    client.command(create_sql)
    logger.info("Created daily_kline table with schema from skill document")


def decimal_round(x, n_digits):
    return x.quantize(Decimal("0." + "0" * n_digits), rounding="ROUND_HALF_UP")


def remove_duplicate_records(df: pd.DataFrame) -> pd.DataFrame:
    original_len = len(df)
    df_no_dups = df.drop_duplicates(["ts_code", "trade_date"])
    duplicates_removed = original_len - len(df_no_dups)

    if duplicates_removed > 0:
        logger.warning(f"Removed {duplicates_removed} duplicate records")

    return df_no_dups


def ensure_date_columns_format(df: pd.DataFrame) -> pd.DataFrame:
    if "trade_date" in df.columns:
        df_copy = df.copy()
        df_copy["trade_date"] = pd.to_datetime(df_copy["trade_date"]).dt.date
        return df_copy
    return df


def convert_to_naive_date(date_series):
    return pd.to_datetime(date_series).dt.date


def insert_stock_data(client, df: pd.DataFrame, symbol: str, retries: int = 3):
    for attempt in range(retries):
        try:
            df_clean = remove_duplicate_records(df)
            df_clean = ensure_date_columns_format(df_clean)

            # Add missing columns with default values
            if "turnover_rate" not in df_clean.columns:
                df_clean["turnover_rate"] = None
            if "fetch_time" not in df_clean.columns:
                df_clean["fetch_time"] = datetime.now()

            # Ensure column order matches table schema
            expected_columns = [
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
                "fetch_time",
            ]

            # Add any missing columns with defaults
            for col in expected_columns:
                if col not in df_clean.columns:
                    if col == "turnover_rate":
                        df_clean[col] = None
                    elif col == "fetch_time":
                        df_clean[col] = datetime.now()
                    else:
                        df_clean[col] = 0.0

            # Reorder columns
            df_clean = df_clean[expected_columns]

            logger.info(f"Inserting {len(df_clean)} records for {symbol}")

            client.insert(
                table="daily_kline",
                data=df_clean,
                settings={"async_insert": 1, "wait_for_async_insert": 1},
            )

            logger.success(f"Inserted {len(df_clean)} records for {symbol}")
            return True

        except Exception as e:
            logger.error(f"Attempt {attempt + 1} failed for {symbol}: {str(e)}")
            if attempt == retries - 1:
                logger.error(f"All {retries} attempts failed for {symbol}")
                return False
            time.sleep(2**attempt)
    return False


def fetch_tdx_data(
    symbol: str, start_date: str, end_date: str
) -> Optional[pd.DataFrame]:
    try:
        # Parse dates
        start_dt = datetime.strptime(start_date, "%Y-%m-%d")
        end_dt = datetime.strptime(end_date, "%Y-%m-%d")
        days_diff = (end_dt - start_dt).days

        # Initialize TDX client
        tdx_client = Quotes.factory(market="std")

        # Get raw code (remove .XSHG/.XSHE suffix)
        raw_code = symbol.split(".")[0]

        # Download bars (frequency=9 for daily, offset=days for recent data)
        # mootdx returns maximum 5000 bars, which is enough for ~20 years
        kline_data = tdx_client.bars(
            symbol=raw_code, frequency=9, offset=min(days_diff + 100, 5000)
        )

        if kline_data is None or kline_data.empty:
            logger.warning(f"No data returned for {symbol}")
            return None

        logger.info(
            f"mootdx returned DataFrame with columns: {kline_data.columns.tolist()}"
        )
        logger.info(f"mootdx returned DataFrame index: {kline_data.index.name}")

        # Convert to DataFrame with proper column names
        df = kline_data.copy()

        # Reset index if needed
        if df.index.name is not None:
            df = df.reset_index()

        column_mapping = {
            "open": "open",
            "high": "high",
            "low": "low",
            "close": "close",
            "volume": "volume",
            "amount": "amount",
        }
        df = df.rename(columns=column_mapping)

        # Add required columns
        df["stock_code"] = raw_code
        df["trade_date"] = pd.to_datetime(df.index).date

        # Filter by date range
        df = df[
            (df["trade_date"] >= start_dt.date()) & (df["trade_date"] <= end_dt.date())
        ]

        if df.empty:
            logger.warning(f"No data in date range for {symbol}")
            return None

        # Add additional calculated fields
        df["pre_close"] = df["close"].shift(1)
        df["change"] = df["close"] - df["pre_close"]
        df["change_pct"] = (df["change"] / df["pre_close"]) * 100
        df["amplitude"] = ((df["high"] - df["low"]) / df["low"]) * 100

        # Fill NaN values for first row
        df["pre_close"] = df["pre_close"].fillna(df["close"])
        df["change"] = df["change"].fillna(0)
        df["change_pct"] = df["change_pct"].fillna(0)
        df["amplitude"] = df["amplitude"].fillna(0)

        # Round to 4 decimal places as per skill document
        decimal_columns = [
            "open",
            "high",
            "low",
            "close",
            "amount",
            "pre_close",
            "change",
            "change_pct",
            "amplitude",
        ]
        for col in decimal_columns:
            if col in df.columns:
                df[col] = df[col].round(4)

        # Ensure volume is integer
        df["volume"] = df["volume"].astype(int)

        logger.info(
            f"Fetched {len(df)} records for {symbol} ({start_date} to {end_date})"
        )
        return df

    except Exception as e:
        logger.error(f"Failed to fetch data for {symbol} from TDX: {e}")
        logger.debug(traceback.format_exc())
        return None


def fetch_and_save_stock_data(
    symbols: List[str], lookback_period: str = "1Y", lookback_range: int = 10
):
    logger.info(
        f"Starting data import for {len(symbols)} symbols with lookback period {lookback_period}"
    )

    # Calculate date ranges based on lookback period
    end_date = datetime.now().strftime("%Y-%m-%d")

    if lookback_period.endswith("Y"):
        years = int(lookback_period.replace("Y", ""))
        start_date = (datetime.now() - timedelta(days=365 * years)).strftime("%Y-%m-%d")
    elif lookback_period.endswith("M"):
        months = int(lookback_period.replace("M", ""))
        start_date = (datetime.now() - timedelta(days=30 * months)).strftime("%Y-%m-%d")
    else:  # Assume days
        days = int(lookback_period.replace("D", ""))
        start_date = (datetime.now() - timedelta(days=days)).strftime("%Y-%m-%d")

    logger.info(f"Date range: {start_date} to {end_date}")

    client = connect_clickhouse()
    reset_daily_kline_table(client)

    successful_imports = 0
    failed_imports = 0

    total_symbols = len(symbols)

    try:
        logger.info("Initializing TDX connection...")
        Quotes.factory(market="std")
    except Exception as e:
        logger.warning(
            f"TDX might not be configured locally: {e}. Will proceed with any cached data if available."
        )

    for idx, symbol in enumerate(symbols):
        logger.info(f"Processing [{idx + 1}/{total_symbols}] {symbol}")

        try:
            data_df = None
            for retry_idx in range(3):
                data_df = fetch_tdx_data(symbol, start_date, end_date)
                if data_df is not None and len(data_df) > 0:
                    break
                logger.info(f"Retry {retry_idx + 1}/3 for {symbol}...")
                time.sleep(2)

            if data_df is not None and len(data_df) > 0:
                logger.info(
                    f"Got {len(data_df)} records for {symbol}. Attempting import..."
                )
                success = insert_stock_data(client, data_df, symbol)

                if success:
                    successful_imports += 1
                    logger.success(
                        f"Successfully imported {len(data_df)} records for {symbol}"
                    )
                else:
                    failed_imports += 1
                    logger.warning(f"Failed to import records for {symbol}")
            else:
                logger.warning(f"No data found for {symbol}")
                data_df = pd.DataFrame(
                    {
                        "ts_code": [],
                        "trade_date": [],
                        "open": [],
                        "high": [],
                        "low": [],
                        "close": [],
                        "vol": [],
                        "amount": [],
                    }
                ).astype(
                    {
                        "ts_code": str,
                        "trade_date": "object",
                        "open": float,
                        "high": float,
                        "low": float,
                        "close": float,
                        "vol": int,
                        "amount": float,
                    }
                )
                failed_imports += 1

        except Exception as e:
            logger.error(f"Error processing {symbol}: {str(e)}")
            failed_imports += 1
            logger.debug(traceback.format_exc())
            continue

    client.close()

    logger.info("=" * 50)
    logger.info(f"Import completed!")
    logger.info(f"Successful imports: {successful_imports}")
    logger.info(f"Failed imports: {failed_imports}")
    logger.info(f"Total processed: {len(symbols)}")
    logger.info("=" * 50)

    return successful_imports, failed_imports


def main():
    stocks_map = {
        "600406": "国电南瑞",
        "002463": "沪电股份",
        "300308": "中际旭创",
        "601899": "紫金矿业",
        "600900": "长江电力",
    }

    stock_codes = []
    for code in stocks_map.keys():
        if code.startswith("60"):
            stock_codes.append(f"{code}.XSHG")
        else:
            stock_codes.append(f"{code}.XSHE")

    logger.info(f"About to import data for symbols: {stock_codes}")

    successful, failed = fetch_and_save_stock_data(stock_codes, lookback_period="1Y")

    if failed > 0:
        logger.warning(
            f"Some stocks failed to import. {successful} succeeded, {failed} failed."
        )
        if successful == 0:
            sys.exit(1)
        else:
            pass
    else:
        logger.info("All data imports completed successfully!")


if __name__ == "__main__":
    main()
