#include <jni.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* Minimal declarations from mpv/client.h. Keeping this bridge independent of
 * generated headers lets it link against the exact libmpv shipped in the AAR. */
typedef struct mpv_handle mpv_handle;

typedef struct mpv_event {
    int event_id;
    int error;
    uint64_t reply_userdata;
    void *data;
} mpv_event;

typedef struct mpv_event_log_message {
    const char *prefix;
    const char *level;
    const char *text;
    int log_level;
} mpv_event_log_message;

typedef enum mpv_format {
    MPV_FORMAT_NONE = 0,
    MPV_FORMAT_STRING = 1,
    MPV_FORMAT_FLAG = 3,
    MPV_FORMAT_INT64 = 4,
    MPV_FORMAT_DOUBLE = 5,
    MPV_FORMAT_NODE = 6,
    MPV_FORMAT_NODE_ARRAY = 7,
    MPV_FORMAT_NODE_MAP = 8,
    MPV_FORMAT_BYTE_ARRAY = 9,
} mpv_format;

typedef struct mpv_byte_array {
    void *data;
    size_t size;
} mpv_byte_array;

typedef struct mpv_node mpv_node;

typedef struct mpv_node_list {
    int num;
    mpv_node *values;
    char **keys;
} mpv_node_list;

struct mpv_node {
    union {
        char *string;
        int flag;
        int64_t int64;
        double double_value;
        mpv_node_list *list;
        mpv_byte_array *byte_array;
    } value;
    mpv_format format;
};

extern int mpv_initialize(mpv_handle *ctx);
extern const char *mpv_error_string(int error);
extern int mpv_request_log_messages(mpv_handle *ctx, const char *min_level);
extern mpv_event *mpv_wait_event(mpv_handle *ctx, double timeout);
extern int mpv_command(mpv_handle *ctx, const char **args);
extern int mpv_command_ret(mpv_handle *ctx, const char **args, mpv_node *result);
extern void mpv_free_node_contents(mpv_node *node);

/* MPVInstance's first member is mpv_handle *mpv in libmpv-android v1.0.0. */
typedef struct mpv_instance_prefix {
    mpv_handle *mpv;
} mpv_instance_prefix;

enum {
    MPV_EVENT_NONE = 0,
    MPV_EVENT_LOG_MESSAGE = 2,
};

static int screenshot_error = 0;

static void append_text(char *output, size_t capacity, const char *text) {
    if (!text || capacity == 0) return;
    size_t used = strlen(output);
    if (used >= capacity - 1) return;
    strncat(output, text, capacity - used - 1);
}

static void drain_logs(mpv_handle *mpv, char *output, size_t capacity) {
    output[0] = '\0';
    if (!mpv) return;

    int examined = 0;
    int recorded = 0;
    char recent[24][256];
    memset(recent, 0, sizeof(recent));
    while (examined++ < 512) {
        mpv_event *event = mpv_wait_event(mpv, 0.0);
        if (!event || event->event_id == MPV_EVENT_NONE) break;
        if (event->event_id != MPV_EVENT_LOG_MESSAGE || !event->data) continue;

        mpv_event_log_message *message = (mpv_event_log_message *)event->data;
        char *line = recent[recorded % 24];
        snprintf(
            line,
            256,
            "%s/%s: %s",
            message->prefix ? message->prefix : "mpv",
            message->level ? message->level : "unknown",
            message->text ? message->text : ""
        );
        for (size_t index = 0; line[index]; index++) {
            if (line[index] == '\n' || line[index] == '\r') line[index] = ' ';
        }
        recorded++;
    }

    /* Errors are normally at the end of mpv's log. Emit newest first so the
     * important lines survive the application report's per-event size limit. */
    int available = recorded < 24 ? recorded : 24;
    for (int index = 0; index < available; index++) {
        int slot = (recorded - 1 - index) % 24;
        if (index > 0) append_text(output, capacity, " | ");
        append_text(output, capacity, recent[slot]);
    }
}

JNIEXPORT jstring JNICALL
Java_dev_jdtech_mpv_MPVLib_nativeInitDetailed(
    JNIEnv *env,
    jobject thiz,
    jlong instance
) {
    (void)thiz;
    mpv_instance_prefix *wrapper = (mpv_instance_prefix *)(intptr_t)instance;
    if (!wrapper || !wrapper->mpv) {
        return (*env)->NewStringUTF(env, "native wrapper has no mpv handle");
    }

    int result = mpv_initialize(wrapper->mpv);
    if (result >= 0) {
        /* Verbose startup logging was requested by the upstream wrapper before
         * initialization. Retain warnings without allowing an undrained event
         * queue to grow during a long thumbnail session. */
        mpv_request_log_messages(wrapper->mpv, "warn");
        return NULL;
    }

    char logs[6144];
    drain_logs(wrapper->mpv, logs, sizeof(logs));
    char result_text[7168];
    snprintf(
        result_text,
        sizeof(result_text),
        "code=%d error=%s%s%s",
        result,
        mpv_error_string(result),
        logs[0] ? " logs=" : "",
        logs
    );
    return (*env)->NewStringUTF(env, result_text);
}

JNIEXPORT jstring JNICALL
Java_dev_jdtech_mpv_MPVLib_nativeDrainDiagnosticLogs(
    JNIEnv *env,
    jobject thiz,
    jlong instance
) {
    (void)thiz;
    mpv_instance_prefix *wrapper = (mpv_instance_prefix *)(intptr_t)instance;
    char logs[6144];
    drain_logs(wrapper ? wrapper->mpv : NULL, logs, sizeof(logs));
    return (*env)->NewStringUTF(env, logs);
}

JNIEXPORT jint JNICALL
Java_dev_jdtech_mpv_MPVLib_nativeCommandDetailed(
    JNIEnv *env,
    jobject thiz,
    jlong instance,
    jobjectArray command
) {
    (void)thiz;
    mpv_instance_prefix *wrapper = (mpv_instance_prefix *)(intptr_t)instance;
    if (!wrapper || !wrapper->mpv || !command) return -4;

    jsize count = (*env)->GetArrayLength(env, command);
    if (count <= 0 || count >= 32) return -4;

    const char *arguments[32] = {NULL};
    jstring strings[32] = {NULL};
    for (jsize index = 0; index < count; index++) {
        strings[index] = (jstring)(*env)->GetObjectArrayElement(env, command, index);
        if (!strings[index]) continue;
        arguments[index] = (*env)->GetStringUTFChars(env, strings[index], NULL);
    }

    int result = mpv_command(wrapper->mpv, arguments);
    for (jsize index = 0; index < count; index++) {
        if (strings[index] && arguments[index]) {
            (*env)->ReleaseStringUTFChars(env, strings[index], arguments[index]);
        }
        if (strings[index]) (*env)->DeleteLocalRef(env, strings[index]);
    }
    return result;
}

static mpv_node *map_value(mpv_node *map, const char *key) {
    if (!map || map->format != MPV_FORMAT_NODE_MAP || !map->value.list) return NULL;
    mpv_node_list *list = map->value.list;
    for (int index = 0; index < list->num; index++) {
        if (list->keys[index] && strcmp(list->keys[index], key) == 0) {
            return &list->values[index];
        }
    }
    return NULL;
}

static void write_int32_little_endian(unsigned char *destination, int32_t value) {
    uint32_t bits = (uint32_t)value;
    destination[0] = (unsigned char)(bits & 0xffu);
    destination[1] = (unsigned char)((bits >> 8) & 0xffu);
    destination[2] = (unsigned char)((bits >> 16) & 0xffu);
    destination[3] = (unsigned char)((bits >> 24) & 0xffu);
}

JNIEXPORT jbyteArray JNICALL
Java_dev_jdtech_mpv_MPVLib_nativeScreenshotRaw(
    JNIEnv *env,
    jobject thiz,
    jlong instance
) {
    (void)thiz;
    screenshot_error = 0;
    mpv_instance_prefix *wrapper = (mpv_instance_prefix *)(intptr_t)instance;
    if (!wrapper || !wrapper->mpv) {
        screenshot_error = -1000;
        return NULL;
    }

    /* Bitmap.copyPixelsFromBuffer() consumes RGBA byte order for Android's
     * ARGB_8888 bitmap storage on the supported little-endian TV ABIs. Feeding
     * mpv's BGRA output directly swaps red and blue (orange becomes cyan).
     * Request RGBA at the source so the native downsampler and Kotlin decoder
     * can remain lossless and allocation-free. */
    const char *arguments[] = {"screenshot-raw", "video", "rgba", NULL};
    mpv_node result = {0};
    int command_result = mpv_command_ret(wrapper->mpv, arguments, &result);
    if (command_result < 0) {
        screenshot_error = command_result;
        return NULL;
    }

    mpv_node *width_node = map_value(&result, "w");
    mpv_node *height_node = map_value(&result, "h");
    mpv_node *stride_node = map_value(&result, "stride");
    mpv_node *data_node = map_value(&result, "data");
    if (!width_node || width_node->format != MPV_FORMAT_INT64 ||
        !height_node || height_node->format != MPV_FORMAT_INT64 ||
        !stride_node || stride_node->format != MPV_FORMAT_INT64 ||
        !data_node || data_node->format != MPV_FORMAT_BYTE_ARRAY ||
        !data_node->value.byte_array || !data_node->value.byte_array->data) {
        screenshot_error = -1001;
        mpv_free_node_contents(&result);
        return NULL;
    }

    int64_t width64 = width_node->value.int64;
    int64_t height64 = height_node->value.int64;
    int64_t stride64 = stride_node->value.int64;
    if (width64 <= 0 || height64 <= 0 || width64 > 4096 || height64 > 2304 ||
        stride64 == 0 || stride64 > INT32_MAX || stride64 < INT32_MIN) {
        screenshot_error = -1002;
        mpv_free_node_contents(&result);
        return NULL;
    }

    int32_t width = (int32_t)width64;
    int32_t height = (int32_t)height64;
    int32_t stride = (int32_t)stride64;
    size_t row_bytes = (size_t)width * 4u;
    size_t source_stride = (size_t)(stride < 0 ? -(int64_t)stride : stride);
    size_t required_source_size = source_stride * (size_t)(height - 1) + row_bytes;
    const int32_t output_width = 320;
    const int32_t output_height = 180;
    const size_t output_row_bytes = (size_t)output_width * 4u;
    size_t pixel_bytes = output_row_bytes * (size_t)output_height;
    size_t total_bytes = 12u + pixel_bytes;
    if (source_stride < row_bytes ||
        data_node->value.byte_array->size < required_source_size ||
        total_bytes > INT32_MAX) {
        screenshot_error = -1003;
        mpv_free_node_contents(&result);
        return NULL;
    }

    unsigned char *packed = (unsigned char *)malloc(total_bytes);
    if (!packed) {
        screenshot_error = -1004;
        mpv_free_node_contents(&result);
        return NULL;
    }
    write_int32_little_endian(packed, output_width);
    write_int32_little_endian(packed + 4, output_height);
    write_int32_little_endian(packed + 8, (int32_t)output_row_bytes);

    /* screenshot-raw can return a full 4K BGRA frame. Downsample inside the
     * isolated native worker before crossing JNI, keeping the Java allocation
     * fixed at 320x180 (about 225 KiB). Preserve aspect ratio with black bars. */
    unsigned char *destination = packed + 12u;
    memset(destination, 0, pixel_bytes);
    for (size_t pixel = 0; pixel < (size_t)output_width * output_height; pixel++) {
        destination[pixel * 4u + 3u] = 0xffu;
    }

    int32_t fitted_width;
    int32_t fitted_height;
    if ((int64_t)width * output_height >= (int64_t)height * output_width) {
        fitted_width = output_width;
        fitted_height = (int32_t)((int64_t)height * output_width / width);
    } else {
        fitted_height = output_height;
        fitted_width = (int32_t)((int64_t)width * output_height / height);
    }
    if (fitted_width < 1) fitted_width = 1;
    if (fitted_height < 1) fitted_height = 1;
    int32_t offset_x = (output_width - fitted_width) / 2;
    int32_t offset_y = (output_height - fitted_height) / 2;

    unsigned char *source_origin = (unsigned char *)data_node->value.byte_array->data;
    if (stride < 0) source_origin += source_stride * (size_t)(height - 1);
    for (int32_t output_y = 0; output_y < fitted_height; output_y++) {
        int32_t source_y = (int32_t)((int64_t)output_y * height / fitted_height);
        const unsigned char *source_row =
            source_origin + (ptrdiff_t)source_y * (ptrdiff_t)stride;
        unsigned char *destination_row = destination +
            ((size_t)(output_y + offset_y) * output_width + offset_x) * 4u;
        for (int32_t output_x = 0; output_x < fitted_width; output_x++) {
            int32_t source_x = (int32_t)((int64_t)output_x * width / fitted_width);
            memcpy(destination_row + (size_t)output_x * 4u,
                   source_row + (size_t)source_x * 4u,
                   4u);
        }
    }

    jbyteArray output = (*env)->NewByteArray(env, (jsize)total_bytes);
    if (output) {
        (*env)->SetByteArrayRegion(
            env,
            output,
            0,
            (jsize)total_bytes,
            (const jbyte *)packed
        );
    }
    free(packed);
    mpv_free_node_contents(&result);
    return output;
}

JNIEXPORT jstring JNICALL
Java_dev_jdtech_mpv_MPVLib_nativeScreenshotRawError(
    JNIEnv *env,
    jobject thiz,
    jlong instance
) {
    (void)thiz;
    (void)instance;
    if (screenshot_error == 0) return (*env)->NewStringUTF(env, "none");
    char message[256];
    if (screenshot_error > -1000) {
        snprintf(
            message,
            sizeof(message),
            "code=%d error=%s",
            screenshot_error,
            mpv_error_string(screenshot_error)
        );
    } else {
        const char *reason = "unknown bridge failure";
        if (screenshot_error == -1000) reason = "missing mpv handle";
        if (screenshot_error == -1001) reason = "unexpected screenshot result map";
        if (screenshot_error == -1002) reason = "unsupported frame dimensions or stride";
        if (screenshot_error == -1003) reason = "incomplete screenshot byte array";
        if (screenshot_error == -1004) reason = "thumbnail allocation failed";
        snprintf(message, sizeof(message), "code=%d error=%s", screenshot_error, reason);
    }
    return (*env)->NewStringUTF(env, message);
}
