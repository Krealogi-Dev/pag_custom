//
//  TGFlutterPageRender.m
//  Tgclub
//
//  Created by 黎敬茂 on 2021/11/25.
//  Copyright © 2021 Tencent. All rights reserved.
//

#import "TGFlutterPagRender.h"
#import <OpenGLES/EAGL.h>
#import <OpenGLES/ES2/gl.h>
#import <OpenGLES/ES2/glext.h>
#import <CoreVideo/CoreVideo.h>
#import <UIKit/UIKit.h>
#include <libpag/PAGPlayer.h>
#include <chrono>
#include <mutex>

@interface TGFlutterPagRender()

@property(nonatomic, strong)PAGSurface *surface;

@property(nonatomic, strong)PAGPlayer* player;

@property(nonatomic, strong)PAGFile* pagFile;

@property(nonatomic, assign)double initProgress;

@property(nonatomic, assign)BOOL endEvent;


@end

static int64_t GetCurrentTimeUS() {
  static auto START_TIME = std::chrono::high_resolution_clock::now();
  auto now = std::chrono::high_resolution_clock::now();
  auto ns = std::chrono::duration_cast<std::chrono::nanoseconds>(now - START_TIME);
  return static_cast<int64_t>(ns.count() * 1e-3);
}

@implementation TGFlutterPagRender
{
    FrameUpdateCallback _frameUpdateCallback;
    PAGEventCallback _eventCallback;
    CADisplayLink *_displayLink;
    int _lastUpdateTs;
    int _repeatCount;
    int64_t start;
    int64_t _currRepeatCount;
    // copyPixelBuffer runs on the raster thread, while start/stop/setProgress run on
    // the main thread. This lock guards the shared playback state and the PAGPlayer.
    NSLock *_lock;
}

// Events can originate from the raster thread (copyPixelBuffer), but the method
// channel must be invoked from the platform (main) thread.
- (void)emitEvent:(NSString *)event {
    PAGEventCallback callback = _eventCallback;
    if (callback == nil) {
        return;
    }
    if ([NSThread isMainThread]) {
        callback(event);
    } else {
        dispatch_async(dispatch_get_main_queue(), ^{
            callback(event);
        });
    }
}

- (CVPixelBufferRef)copyPixelBuffer {
    BOOL sendEnd = NO;
    BOOL sendRepeat = NO;
    CVPixelBufferRef target = NULL;

    [_lock lock];
    if (_player == nil || _surface == nil) {
        [_lock unlock];
        return NULL;
    }
    int64_t duration = [_player duration];
    if(duration <= 0){
        duration = 1;
    }

    int64_t timestamp = GetCurrentTimeUS();
    if(start <= 0){
        start = timestamp;
    }
    auto count = (timestamp - start) / duration;
    double value = 0;
    if (_repeatCount >= 0 && count >= _repeatCount) {
        value = 1;
        if(!_endEvent){
            _endEvent = YES;
            sendEnd = YES;
        }
    } else {
        _endEvent = NO;
        double playTime = (timestamp - start) % duration;
        value = static_cast<double>(playTime) / duration;
        if (_currRepeatCount < count) {
            _currRepeatCount = count;
            sendRepeat = YES;
        }
    }
    [_player setProgress:value];
    [_player flush];
    target = [_surface getCVPixelBuffer];
    CVBufferRetain(target);
    [_lock unlock];

    if (sendEnd) {
        [self emitEvent:EventEnd];
    }
    if (sendRepeat) {
        [self emitEvent:EventRepeat];
    }
    return target;
}

- (instancetype)initWithPagData:(NSData*)pagData
                       progress:(double)initProgress
            frameUpdateCallback:(FrameUpdateCallback)frameUpdateCallback
                  eventCallback:(PAGEventCallback)eventCallback
{
    if (self = [super init]) {
        _lock = [[NSLock alloc] init];
        _frameUpdateCallback = frameUpdateCallback;
        _eventCallback = eventCallback;
        _initProgress = initProgress;
        if(pagData){
            _pagFile = [PAGFile Load:pagData.bytes size:pagData.length];
            _player = [[PAGPlayer alloc] init];
            [_player setComposition:_pagFile];
            _surface = [PAGSurface MakeFromGPU:CGSizeMake(_pagFile.width, _pagFile.height)];
            [_player setSurface:_surface];
            [_player setProgress:initProgress];
            [_player flush];
            _frameUpdateCallback();
        }
    }
    return self;
}

- (void)startRender
{
    if (!_displayLink) {
        _displayLink = [CADisplayLink displayLinkWithTarget:self selector:@selector(update)];
        [_displayLink addToRunLoop:[NSRunLoop mainRunLoop] forMode:NSRunLoopCommonModes];
    }
    [_lock lock];
    if(start <= 0){
       start = GetCurrentTimeUS();
    }
    [_lock unlock];
    [self emitEvent:EventStart];
}

- (void)stopRender
{
    if (_displayLink) {
        [_displayLink invalidate];
        _displayLink = nil;
    }
    BOOL sendEnd = NO;
    [_lock lock];
    [_player setProgress:_initProgress];
    [_player flush];
    if(!_endEvent){
        _endEvent = YES;
        sendEnd = YES;
    }
    [_lock unlock];
    _frameUpdateCallback();
    if (sendEnd) {
        [self emitEvent:EventEnd];
    }
    [self emitEvent:EventCancel];
}

- (void)pauseRender{
    if (_displayLink) {
        [_displayLink invalidate];
        _displayLink = nil;
    }
}
- (void)setRepeatCount:(int)repeatCount{
    _repeatCount = repeatCount;
}

- (void)setProgress:(double)progress{
    [_lock lock];
    [_player setProgress:progress];
    [_player flush];
    [_lock unlock];
    _frameUpdateCallback();
}

- (NSArray<NSString *> *)getLayersUnderPoint:(CGPoint)point{
    [_lock lock];
    NSArray<PAGLayer*>* layers = [_player getLayersUnderPoint:point];
    [_lock unlock];
    NSMutableArray<NSString *> *layerNames = [[NSMutableArray alloc] init];
    for (PAGLayer *layer in layers) {
        [layerNames addObject:layer.layerName];
    }
    return layerNames;
}

- (CGSize)size{
    return CGSizeMake(_pagFile.width, _pagFile.height);
}

- (void)update
{
    _frameUpdateCallback();
}

- (void)releaseRender{
    if (_displayLink) {
        [_displayLink invalidate];
        _displayLink = nil;
    }
}

- (void)dealloc {
    _frameUpdateCallback = nil;
    _eventCallback = nil;
    _surface = nil;
    self.pagFile = nil;
    self.player = nil;
}
@end
