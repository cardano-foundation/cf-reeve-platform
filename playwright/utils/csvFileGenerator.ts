import * as fs from "fs";
import * as path from "path";
import {unlink} from "node:fs/promises";

/**
 * Create CSV data
 * @param columns Array of column names
 * @param rows Array of rows (each row is an array of values in order)
 * @param filename Output CSV filename
 */
export async function saveCSV(
    columns: string[],
    rows: any[][],
    filename: string
): Promise<string> {
    const folderPath = "./resources";

    if (columns.length === 0) {
        throw new Error("You must provide at least one column.");
    }

    // Escape values that contain quotes, commas or newlines
    const escape = (value: any): string => {
        if (value == null) return "";
        const valueAsString = String(value);
        if (/[",\n]/.test(valueAsString)) {
            return `"${valueAsString.replace(/"/g, '""')}"`;
        }
        return valueAsString;
    };

    // Build CSV content
    const header = columns.join(",");
    const data = rows.map(r =>
        columns.map((_, i) => escape(r[i] ?? "")).join(",")
    );
    const csvContent = [header, ...data].join("\n");

    // Ensure folder exists
    fs.mkdirSync(folderPath, { recursive: true });

    // Save file
    const fullPath = path.join(folderPath, filename);
    fs.writeFileSync(fullPath, csvContent, "utf-8");

    //return file
    return fullPath;
}

export async function deleteFile(fullPath: string): Promise<void> {
    try {
        await unlink(fullPath);
        console.log(`✓ File successfully deleted`);
    } catch (error) {
        console.error(`✗ Error trying to delete:`, error);
        throw error;
    }
}
