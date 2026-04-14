#!/usr/bin/env python3
"""
Simple script to import selected Chinese stocks into ClickHouse.
Based on exact schema from skill document.
"""

import time
import random
from datetime import datetime, timedelta
import pandas as pd
import clickhouse_connect
from mootdx.quotes import Quotes


def main():
    # Selected stocks
    stocks = {
        "600406": "国电南瑞",
        "002463": "沪电股份",
        "300308": "中际旭创",
        "601899": "紫金矿业",
        "600900": "长江电力",
    }

    # Date range: last 1 year
    end_date = datetime.now().date()
    start_date = end_date - timedelta(days=365)

    print(f"Importing {len(stocks)} stocks from {start_date} to {end_date}")

    # Connect to ClickHouse
    client = clickhouse_connect.get_client(
        host="localhost",
        port=8123,
        username="default",
        password="harness123",
        database="harness_db",
    )

    # Create table (drop if exists)
    client.command("DROP TABLE IF EXISTS daily_kline")

    create_table_sql = """
    CREATE TABLE daily_kline (
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
    ) ENGINE = MergeTree()
    ORDER BY (stock_code, trade_date)
    PARTITION BY toYYYYMM(trade_date)
    TTL trade_date + INTERVAL 10 YEAR
    """
    client.command(create_table_sql)
    print("Created daily_kline table")

    # Initialize TDX client
    tdx_client = Quotes.factory(market="std")

    successful = 0
    failed = 0

    for code, name in stocks.items():
        print(f"\nProcessing {code} ({name})...")

        try:
            # Download data
            kline_data = tdx_client.bars(symbol=code, frequency=9, offset=5000)

            if kline_data is None or kline_data.empty:
                print(f"No data for {code}")
                failed += 1
                continue

            # Convert to DataFrame
            df = kline_data.copy()

            # Basic column renaming - mootdx uses 'vol' instead of 'volume'
            if "vol" in df.columns:
                df["volume"] = df["vol"]

            # Add stock code
            df["stock_code"] = code

            # Convert index to date (mootdx returns DatetimeIndex)
            df["trade_date"] = df.index.date

            # Reset index
            df = df.reset_index(drop=True)

            # Filter by date range
            df = df[(df["trade_date"] >= start_date) & (df["trade_date"] <= end_date)]

            if df.empty:
                print(f"No data in range for {code}")
                failed += 1
                continue

            # Calculate required fields from skill document
            df["pre_close"] = df["close"].shift(1).fillna(df["close"])
            df["change"] = df["close"] - df["pre_close"]
            df["change_pct"] = (df["change"] / df["pre_close"]) * 100
            df["amplitude"] = ((df["high"] - df["low"]) / df["low"]) * 100

            # Set null for turnover_rate (not in mootdx basic data)
            df["turnover_rate"] = None

            # Ensure volume is integer
            if "volume" in df.columns:
                df["volume"] = df["volume"].astype(int)

            # Select only the columns we need, in correct order
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

            # Create final DataFrame with correct column order
            final_df = pd.DataFrame()
            for col in final_columns:
                if col in df.columns:
                    final_df[col] = df[col]
                else:
                    if col == "turnover_rate":
                        final_df[col] = None
                    else:
                        final_df[col] = 0.0

            print(f"Prepared {len(final_df)} records for {code}")

            # Insert into ClickHouse
            client.insert_df("daily_kline", final_df)
            print(f"Successfully imported {len(final_df)} records")
            successful += 1

        except Exception as e:
            print(f"Error with {code}: {e}")
            failed += 1

        # Small delay to avoid rate limiting
        time.sleep(random.uniform(0.5, 1.0))

    # Verify data was inserted
    if successful > 0:
        result = client.query(
            "SELECT COUNT(*) as total, COUNT(DISTINCT stock_code) as stocks FROM daily_kline"
        )
        print(
            f"\nVerification: {result.result_rows[0][0]} total records, {result.result_rows[0][1]} stocks"
        )

    client.close()

    print(f"\n{'=' * 50}")
    print(f"Import completed:")
    print(f"  Successful: {successful}")
    print(f"  Failed: {failed}")
    print(f"{'=' * 50}")


if __name__ == "__main__":
    main()
