cmake_minimum_required(VERSION 3.22.1)

project("sefirah_worker")

add_executable(libsefirah_worker.so starter.c misc.c)

if (NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    add_custom_command(TARGET libsefirah_worker.so POST_BUILD
            COMMAND ${CMAKE_STRIP} --remove-section=.comment "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}/libsefirah_worker.so")
endif ()
