LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE := timetoy_c
LOCAL_SRC_FILES := cengine.cpp

LOCAL_CPPFLAGS += -std=c++14 -O3
LOCAL_LDLIBS += -llog

include $(BUILD_SHARED_LIBRARY)
