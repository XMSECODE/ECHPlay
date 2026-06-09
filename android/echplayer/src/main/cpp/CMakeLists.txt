cmake_minimum_required(VERSION 3.22.1)

project("echplayer")

add_library(
        echplayer
        SHARED
        native-lib.cpp
        NativePlayer.cpp
        GlVideoRenderer.cpp
)

set(FFMPEG_INCLUDE_DIR ${CMAKE_SOURCE_DIR}/ffmpeg/include)
set(FFMPEG_LIB_DIR ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})

include_directories(${FFMPEG_INCLUDE_DIR})

add_library(avcodec SHARED IMPORTED)
set_target_properties(avcodec PROPERTIES IMPORTED_LOCATION ${FFMPEG_LIB_DIR}/libavcodec.so)

add_library(avformat SHARED IMPORTED)
set_target_properties(avformat PROPERTIES IMPORTED_LOCATION ${FFMPEG_LIB_DIR}/libavformat.so)

add_library(avutil SHARED IMPORTED)
set_target_properties(avutil PROPERTIES IMPORTED_LOCATION ${FFMPEG_LIB_DIR}/libavutil.so)

add_library(swresample SHARED IMPORTED)
set_target_properties(swresample PROPERTIES IMPORTED_LOCATION ${FFMPEG_LIB_DIR}/libswresample.so)

add_library(swscale SHARED IMPORTED)
set_target_properties(swscale PROPERTIES IMPORTED_LOCATION ${FFMPEG_LIB_DIR}/libswscale.so)

find_library(log-lib log)
find_library(android-lib android)
find_library(egl-lib EGL)
find_library(glesv2-lib GLESv2)

target_link_libraries(
        echplayer

        avcodec
        avformat
        avutil
        swresample
        swscale

        ${android-lib}
        ${egl-lib}
        ${glesv2-lib}
        ${log-lib}
)
