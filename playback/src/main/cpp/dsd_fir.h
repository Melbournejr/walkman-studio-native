// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Walkman Studio contributors
// Derived from dsd-format.js filterTable/DSDPCMReader, revision 6e4c13fcba29d3a4eb3ccd67a86e1c8aedd2bb8a.
#pragma once
#include <cstdint>
#include <memory>
#include <vector>
namespace walkman {
struct FirTable {int ratio;int groups;std::vector<float> lookup;};
class DsdFir {
 public:
  DsdFir(int ratio,bool lsb);
  void decode(const uint8_t* planar,int plane_stride,int channels,int frames,float* interleaved) const;
  int groups()const{return table_->groups;}
  int step()const{return table_->ratio/8;}
 private:std::shared_ptr<const FirTable> table_;
};
}
