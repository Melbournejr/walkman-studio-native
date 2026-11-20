// SPDX-License-Identifier: GPL-3.0-or-later
// Independent float64 formula uses libstdc++ Bessel I0, not the production series/LUT.
#include "dsd_fir.h"
#include <algorithm>
#include <chrono>
#include <cmath>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <vector>
#include <iomanip>
using walkman::DsdFir;
namespace {
constexpr double pi=3.14159265358979323846264338327950288;
void expect(bool ok,const char* message){if(!ok)throw std::runtime_error(message);}
std::vector<double> reference_coefficients(int ratio){
 const int taps=ratio*32;std::vector<double> result(taps);double sum=0;
 for(int i=0;i<taps;i++){
  const double x=i-(taps-1)*.5,t=2.0*i/(taps-1)-1,fc=.32/ratio;
  result[i]=std::sin(2*pi*fc*x)/(pi*x)*std::cyl_bessel_i(0.0,8.6*std::sqrt(1-t*t))/std::cyl_bessel_i(0.0,8.6);sum+=result[i];
 }
 for(double& x:result) { x/=sum; }
 return result;
}
double test_reference(int ratio,bool lsb){
 DsdFir fir(ratio,lsb);const int frames=257,stride=(frames-1)*fir.step()+fir.groups();
 std::vector<uint8_t> bytes(stride*2);uint32_t seed=0x3dbab123;
 for(auto& x:bytes){seed=seed*1664525+1013904223;x=static_cast<uint8_t>(seed>>24);}
 std::vector<float> result(frames*2);fir.decode(bytes.data(),stride,2,frames,result.data());
 const auto coeff=reference_coefficients(ratio);double maximum=0;
 for(int channel=0;channel<2;channel++)for(int frame=0;frame<frames;frame++){
  double value=0;for(int tap=0;tap<ratio*32;tap++){
   const uint8_t packed=bytes[channel*stride+frame*fir.step()+tap/8];const int bit=lsb?tap%8:7-tap%8;
   value+=((packed>>bit)&1?1.0:-1.0)*coeff[tap];
  }
  maximum=std::max(maximum,std::abs(value-result[frame*2+channel]));
 }
 expect(maximum<=1e-5,"Float LUT differs from float64 reference >1e-5 FS");
 std::vector<float> split(result.size());fir.decode(bytes.data(),stride,2,113,split.data());
 fir.decode(bytes.data()+113*fir.step(),stride,2,frames-113,split.data()+113*2);
 expect(result==split,"Block/seek FIR windows differ");return maximum;
}
struct Result{int rate;double amplitude_error,frequency_error,rtf,milliseconds,max_reference;};
Result benchmark(int ratio){
 const int rate=88200*ratio,frames=88200*2,half=ratio*2,groups=ratio*4,step=ratio/8;
 const int stride=(frames-1)*step+groups;std::vector<uint8_t> input(stride*2,0x99);
 const double amplitude=std::pow(10.0,-12.0/20);double accumulator=0;
 for(int i=0;i<rate*2;i++){
  const double value=amplitude*std::sin(2*pi*1000*i/rate);accumulator+=value;
  const bool bit=accumulator>=0;accumulator-=bit?1.0:-1.0;
  const int at=half+i/8,mask=1<<(i%8);
  for(int channel=0;channel<2;channel++)if(bit)input[channel*stride+at]|=mask;else input[channel*stride+at]&=~mask;
 }
 DsdFir fir(ratio,true);std::vector<float> pcm(frames*2);
 const auto begin=std::chrono::steady_clock::now();
 for(int first=0;first<frames;first+=8192)fir.decode(input.data()+first*step,stride,2,std::min(8192,frames-first),pcm.data()+first*2);
 const double seconds=std::chrono::duration<double>(std::chrono::steady_clock::now()-begin).count();
 double sine=0,cosine=0,power=0;int count=0;std::vector<double> crossings;
 for(int i=256;i<frames-256;i++){
  expect(pcm[i*2]==pcm[i*2+1],"Stereo channel mismatch");const double x=pcm[i*2];
  sine+=x*std::sin(2*pi*1000*i/88200);cosine+=x*std::cos(2*pi*1000*i/88200);power+=x*x;count++;
  if(pcm[(i-1)*2]<=0 && x>0)crossings.push_back(i-1-pcm[(i-1)*2]/(x-pcm[(i-1)*2]));
 }
 const double measured=2*std::sqrt(sine*sine+cosine*cosine)/count;
 const double db=20*std::log10(measured/amplitude);
 expect(crossings.size()>1000,"Too few sine crossings");
 const double frequency=(crossings.size()-1)*88200.0/(crossings.back()-crossings.front());
 expect(std::abs(db)<=.1,"Sine amplitude error >0.1dB");expect(std::abs(frequency-1000)<=.1,"Sine frequency error >0.1Hz");
 expect(power>0,"Empty PCM");
 return{rate,db,frequency-1000,seconds/2,seconds*1000,std::max(test_reference(ratio,true),test_reference(ratio,false))};
}
}
int main(int argc,char** argv){
 try{
  expect(argc==2,"Need JSON output path");std::vector<Result> results;
  for(int ratio:{32,64,128})results.push_back(benchmark(ratio));
  bool rejected=false;try{DsdFir invalid(256,true);}catch(const std::invalid_argument&){rejected=true;}expect(rejected,"DSD512 was accepted");
  std::ofstream out(argv[1]);out<<std::setprecision(12)<<"{\n  \"environment\":\"HOST SIMULATION x86_64 Linux; not Snapdragon8Gen3\",\n  \"backend\":\"same production C++ FIR, g++ -O3, one CPU thread\",\n  \"source\":\"synthetic first-order sigma-delta sine1000Hz -12dBFS,2seconds,stereo\",\n  \"ioIncluded\":false,\n  \"timingSamplesPerRate\":1,\n  \"limits\":{\"amplitudeDb\":0.1,\"frequencyHz\":0.1,\"maxAbsFloat64\":0.00001,\"deviceTargetRtf\":0.25},\n  \"results\":[\n";
  for(size_t i=0;i<results.size();i++){const auto& r=results[i];if(i)out<<",\n";out<<"    {\"dsdRate\":"<<r.rate<<",\"outputRate\":88200,\"amplitudeErrorDb\":"<<r.amplitude_error<<",\"frequencyErrorHz\":"<<r.frequency_error<<",\"maxAbsFloat64\":"<<r.max_reference<<",\"decodeMs\":"<<r.milliseconds<<",\"rtf\":"<<r.rtf<<"}";}
  out<<"\n  ],\n  \"notVerified\":[\"Xiaomi14Pro\",\"Snapdragon8Gen3\",\"Android16 HyperOS\",\"SAF IO latency\",\"actual DAC/BT\"]\n}\n";out.close();
  std::cout<<"Native FIR reference, sine, bit orders and block/seek equivalence passed\n";return 0;
 }catch(const std::exception& e){std::cerr<<e.what()<<'\n';return 1;}
}
