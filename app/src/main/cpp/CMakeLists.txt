cmake_minimum_required(VERSION 3.22.1)

project(hur_soft_hevc)

add_library(hur_soft_hevc SHARED ffmpeg_hevc_decoder.cpp)

target_compile_features(hur_soft_hevc PRIVATE cxx_std_17)
target_compile_options(hur_soft_hevc PRIVATE -Wall -Wextra -Werror)

find_library(log-lib log)
find_library(android-lib android)

set(FFMPEG_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/ffmpeg")
set(FFMPEG_INCLUDE_DIR "${FFMPEG_ROOT}/include")
set(FFMPEG_LIB_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")

set(FFMPEG_LIBS
    "${FFMPEG_LIB_DIR}/libavcodec.so"
    "${FFMPEG_LIB_DIR}/libavutil.so"
    "${FFMPEG_LIB_DIR}/libswscale.so"
)

set(HUR_FFMPEG_READY FALSE)
if(EXISTS "${FFMPEG_INCLUDE_DIR}/libavcodec/avcodec.h")
    if(EXISTS "${FFMPEG_INCLUDE_DIR}/libavutil/imgutils.h")
        if(EXISTS "${FFMPEG_INCLUDE_DIR}/libswscale/swscale.h")
            if(EXISTS "${FFMPEG_LIB_DIR}/libavcodec.so")
                if(EXISTS "${FFMPEG_LIB_DIR}/libavutil.so")
                    if(EXISTS "${FFMPEG_LIB_DIR}/libswscale.so")
                        set(HUR_FFMPEG_READY TRUE)
                    endif()
                endif()
            endif()
        endif()
    endif()
endif()

message(STATUS "HUR FFmpeg for ${ANDROID_ABI}: ${HUR_FFMPEG_READY} (${FFMPEG_LIB_DIR})")

if(HUR_FFMPEG_READY)
    target_compile_definitions(hur_soft_hevc PRIVATE HUR_HAVE_FFMPEG=1)
    target_include_directories(hur_soft_hevc PRIVATE "${FFMPEG_INCLUDE_DIR}")
    target_link_libraries(hur_soft_hevc PRIVATE ${FFMPEG_LIBS})
else()
    target_compile_definitions(hur_soft_hevc PRIVATE HUR_HAVE_FFMPEG=0)
endif()

target_link_libraries(hur_soft_hevc PRIVATE ${log-lib} ${android-lib})
