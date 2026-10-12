#!/usr/bin/env bash
# 검증용 컨테이너·네트워크·볼륨·이미지를 모두 지운다. (프로젝트 이름 tftverify 범위만)
source "$(dirname "$0")/common.sh"
dc down -v --rmi local
