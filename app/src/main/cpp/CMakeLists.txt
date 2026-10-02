cmake_minimum_required(VERSION 3.22.1)
project(alacjni)

add_library(alacjni SHARED
    alac_jni.cpp
    alac/ALACEncoder.cpp
    alac/ALACBitUtilities.c
    alac/EndianPortable.c
    alac/ag_enc.c
    alac/ag_dec.c
    alac/dp_enc.c
    alac/matrix_enc.c
)

target_include_directories(alacjni PRIVATE alac)

target_compile_options(alacjni PRIVATE -O2)

target_link_libraries(alacjni log)
