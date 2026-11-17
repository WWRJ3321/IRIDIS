set(SD_VERSION      "unknown")
set(SD_BUILD_COMMIT "3e92bf9")
set(SD_SHARED_LIB    OFF)


####### Expanded from @PACKAGE_INIT@ by configure_package_config_file() #######
####### Any changes to this file will be overwritten by the next CMake run ####
####### The input file was stable-diffusion-config.cmake.in                            ########

get_filename_component(PACKAGE_PREFIX_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../" ABSOLUTE)

macro(set_and_check _var _file)
  set(${_var} "${_file}")
  if(NOT EXISTS "${_file}")
    message(FATAL_ERROR "File or directory ${_file} referenced by variable ${_var} does not exist !")
  endif()
endmacro()

macro(check_required_components _NAME)
  foreach(comp ${${_NAME}_FIND_COMPONENTS})
    if(NOT ${_NAME}_${comp}_FOUND)
      if(${_NAME}_FIND_REQUIRED_${comp})
        set(${_NAME}_FOUND FALSE)
      endif()
    endif()
  endforeach()
endmacro()

####################################################################################

set_and_check(SD_INCLUDE_DIR "${PACKAGE_PREFIX_DIR}/include")
set_and_check(SD_LIB_DIR     "${PACKAGE_PREFIX_DIR}/lib")
set(SD_BIN_DIR "${PACKAGE_PREFIX_DIR}/bin")

include(CMakeFindDependencyMacro)
find_dependency(ggml REQUIRED HINTS "${SD_LIB_DIR}/cmake")
find_dependency(Threads REQUIRED)
if(OFF)
    find_dependency(CUDAToolkit REQUIRED)
endif()

if(NOT TARGET stable-diffusion)
    find_library(stable-diffusion_LIBRARY stable-diffusion
        REQUIRED
        HINTS "${SD_LIB_DIR}"
        NO_CMAKE_FIND_ROOT_PATH
    )

    add_library(stable-diffusion UNKNOWN IMPORTED)
    set_target_properties(stable-diffusion
        PROPERTIES
            INTERFACE_INCLUDE_DIRECTORIES "${SD_INCLUDE_DIR}"
            INTERFACE_LINK_LIBRARIES "ggml::ggml;Threads::Threads"
            IMPORTED_LINK_INTERFACE_LANGUAGES "CXX"
            IMPORTED_LOCATION "${stable-diffusion_LIBRARY}"
            INTERFACE_COMPILE_FEATURES "c_std_11;cxx_std_17"
            POSITION_INDEPENDENT_CODE ON)

    if(OFF)
        set_property(TARGET stable-diffusion APPEND PROPERTY INTERFACE_LINK_LIBRARIES CUDA::cuda_driver)
    endif()

    if(SD_SHARED_LIB)
        target_compile_definitions(stable-diffusion
            INTERFACE SD_BUILD_SHARED_LIB)
    endif()
endif()

check_required_components(stable-diffusion)
