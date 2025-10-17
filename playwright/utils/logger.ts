import { configure, getLogger } from "log4js";

configure({
    appenders: {
        app: { type: "file", filename: "./.logs/test-run.log" },
        out: { type: "stdout" }
    },
    categories: {
        default: {
            appenders: ["app", "out"],
            level: "debug"
        }
    }
});

export const log = getLogger();