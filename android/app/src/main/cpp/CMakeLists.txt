cmake_minimum_required(VERSION 3.22.1)

project("hooru_native")

add_library(
    hooru_native
    SHARED
    FastJpegEncoder.cpp
)

target_link_options(
    hooru_native
    PRIVATE
    "-Wl,-z,common-page-size=16384"
    "-Wl,-z,max-page-size=16384"
)

find_library(
    log-lib
    log
)

find_library(
    jnigraphics-lib
    jnigraphics
)

target_link_libraries(
    hooru_native
    ${log-lib}
    ${jnigraphics-lib}
)
