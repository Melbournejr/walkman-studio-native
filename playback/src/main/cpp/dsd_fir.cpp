// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Walkman Studio contributors
// FIR/window/LUT algorithm adapted from Walkman Studio dsd-format.js (GPL-3.0-or-later).
#include "dsd_fir.h"
#include <cmath>
#include <algorithm>
#include <map>
#include <mutex>
#include <stdexcept>
namespace walkman {
namespace {
constexpr double pi=3.14159265358979323846264338327950288;
double bessel0(double x){double sum=1,term=1;for(int k=1;k<40;k++){term*=x*x/(4.0*k*k);sum+=term;if(term<sum*1e-14)break;}return sum;}
std::mutex table_mutex;
std::map<int,std::shared_ptr<const FirTable>> tables;
std::shared_ptr<const FirTable> make_table(int ratio,bool lsb){
 if(ratio!=32 && ratio!=64 && ratio!=128)throw std::invalid_argument("DSD ratio must be32/64/128");
 std::lock_guard<std::mutex> lock(table_mutex);const int key=ratio*2+(lsb?1:0);
 auto found=tables.find(key);if(found!=tables.end())return found->second;
 auto table=std::make_shared<FirTable>();table->ratio=ratio;const int taps=ratio*32;table->groups=taps/8;
 const double beta=8.6,denominator=bessel0(beta),cutoff=.32/ratio;std::vector<double> coefficients(taps);double sum=0;
 for(int n=0;n<taps;n++){
  const double position=n-(taps-1)/2.0;const double sinc=position==0?2*cutoff:std::sin(2*pi*cutoff*position)/(pi*position);
  const double normalized=2.0*n/(taps-1)-1;
  coefficients[n]=sinc*bessel0(beta*std::sqrt(std::max(0.0,1-normalized*normalized)))/denominator;sum+=coefficients[n];
 }
 table->lookup.resize(table->groups*256);
 for(int group=0;group<table->groups;group++)for(int byte=0;byte<256;byte++){
  double value=0;for(int bit=0;bit<8;bit++)value+=(((byte>>(lsb?bit:7-bit))&1)?1.0:-1.0)*coefficients[group*8+bit]/sum;
  table->lookup[group*256+byte]=static_cast<float>(value);
 }
 tables.emplace(key,table);return table;
}
}
DsdFir::DsdFir(int ratio,bool lsb):table_(make_table(ratio,lsb)){}
void DsdFir::decode(const uint8_t* input,int stride,int channels,int frames,float* output)const{
 if(!input || !output || channels<1 || channels>2 || frames<1 || frames>8192 || stride<(frames-1)*step()+groups())throw std::invalid_argument("Invalid FIR window");
 const float* lut=table_->lookup.data();const int count=groups();const int advance=step();
 for(int channel=0;channel<channels;channel++){
  const uint8_t* plane=input+channel*stride;
  for(int frame=0;frame<frames;frame++){
   const uint8_t* bytes=plane+frame*advance;double value=0;
   for(int group=0;group<count;group++)value+=lut[group*256+bytes[group]];
   output[frame*channels+channel]=static_cast<float>(value);
  }
 }
}
}
