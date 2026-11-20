# SPDX-License-Identifier: GPL-3.0-or-later
cmake_minimum_required(VERSION 3.22.1)
project(walkman_dsd LANGUAGES CXX)
add_library(walkman_dsd SHARED dsd_fir.cpp dsd_jni.cpp)
target_compile_features(walkman_dsd PRIVATE cxx_std_17)
target_compile_options(walkman_dsd PRIVATE -O3 -Wall -Wextra -Werror -ffp-contract=off)
target_link_options(walkman_dsd PRIVATE -Wl,-z,max-page-size=16384)
