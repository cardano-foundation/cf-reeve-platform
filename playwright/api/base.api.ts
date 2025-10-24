import {APIRequestContext, APIResponse} from "@playwright/test";

import {log} from "../utils/logger";

const returnLoggedResponse = async (
    response: APIResponse,
    endpoint: string,
    payload?: object,
    isBodyNotSecret = true
) => {
    log.info(`Request URL: ${endpoint}`);
    if (typeof payload !== "undefined" && isBodyNotSecret) {
        log.info(`Request params/body:\n${JSON.stringify(payload, null, 2)}`);
    }
    log.info(`Response status: ${response.status()}`);
    if (response.headers()["content-type"]?.includes("application/json") && isBodyNotSecret) {
        log.info(`Response body:\n${JSON.stringify(await response.json(), null, 2)}`);
    }
    return response;
};

export const postForm = async (
    request: APIRequestContext,
    endpoint: string,
    form?: { [key: string]: string },
    headers?: { [key: string]: string },
    isBodyNotSecret = true
) =>
    returnLoggedResponse(
        await request.post(endpoint, {
            form,
            headers
        }),
        endpoint,
        form,
        isBodyNotSecret
    );
export const getData = async (
    request: APIRequestContext,
    endpoint: string,
    params?: { [key: string]: string | number | boolean },
    headers?: { [key: string]: string },
    isBodyNotSecret = true
) =>
    returnLoggedResponse(
        await request.get(endpoint, {
            headers,
            params
        }),
        endpoint,
        params,
        isBodyNotSecret
    );
export const postFormData = async (
    request: APIRequestContext,
    endpoint: string,
    multipart?: {[key: string]: any},
    headers?: {[key: string]: string},
    isBodyNotSecret = true
) => {
    return returnLoggedResponse(
        await request.post(endpoint, {
            headers,
            multipart
        }),
        endpoint,
        multipart,
        isBodyNotSecret
    );
}
export const postData = async (
    request: APIRequestContext,
    endpoint: string,
    data?: {[key: string]: any},
    headers?: {[key: string]: string},
    params?: { [key: string]: string | number | boolean },
    isBodyNotSecret = true
) => {
    return returnLoggedResponse(
        await request.post(endpoint,{
            headers,
            data
        }),
        endpoint,
        data,
        isBodyNotSecret
    )
}