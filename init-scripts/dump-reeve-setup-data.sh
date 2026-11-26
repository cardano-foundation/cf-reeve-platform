#!/bin/bash

# === Configuration ===
DB_NAME="lob"
DB_USER="postgres"
DB_PASSWORD="postgres"
DB_HOST="localhost"
DB_PORT="5432"
OUTPUT_DIR="./generated/exports"
SCHEMA_NAME="lob_service"
MERGED_FILE="./generated/all_tables_insert_only.sql"

# List of tables (without schema prefix)
TABLES=("organisation"
"organisation_account_event"
"organisation_chart_of_account"
"organisation_chart_of_account_sub_type"
"organisation_chart_of_account_type"
"organisation_cost_center"
"organisation_currency"
"organisation_project"
"organisation_ref_codes"
"organisation_report_setup"
"organisation_report_setup_field"
"organisation_report_setup_field_subtype_mapping"
"organisation_vat"

)

# Create output directory if it doesn't exist
mkdir -p "$OUTPUT_DIR"

# Export each table
for TABLE in "${TABLES[@]}"; do
  QUALIFIED_TABLE="${SCHEMA_NAME}.${TABLE}"
  OUTPUT_FILE="$OUTPUT_DIR/${TABLE}.sql"
  echo "Exporting $QUALIFIED_TABLE to $OUTPUT_FILE"

  PGPASSWORD="$DB_PASSWORD" pg_dump -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" \
    -t "$QUALIFIED_TABLE" --data-only --inserts -f "$OUTPUT_FILE"

  if [ $? -eq 0 ]; then
    echo "✅ Successfully exported $QUALIFIED_TABLE"
  else
    echo "❌ Failed to export $QUALIFIED_TABLE"
  fi
done



# Clean up previous merged file if it exists
rm -f "$MERGED_FILE"

# Merge all .sql files into one, keeping only INSERT statements
for FILE in "$OUTPUT_DIR"/*.sql; do
  echo "-- INSERTs from $FILE" >> "$MERGED_FILE"
  grep '^INSERT INTO' "$FILE" >> "$MERGED_FILE"
  echo -e "\n" >> "$MERGED_FILE"
done

echo "✅ All SQL files merged into $MERGED_FILE"
