#!/usr/bin/env python3

import pickle
import os
import subprocess
import re
import io
import time

timeRegex = "TIME: .+ (\\d+:\\d+:\\d+)"
peerRegex = "FROM: (\\d+\\.\\d+\\.\\d+\\.\\d+)"
updateStartRegex = "ANNOUNCE"
prefixRegex = "\\d+\\.\\d+\\.\\d+\\.\\d+/\\d+"

def convertTS(stamp):
    tokens = stamp.split(":")
    result = float(tokens[0]) * 3600 + float(tokens[1]) * 60 + float(tokens[2])
    return result

def convertDate(dayCounter):
    retStr = ""
    if dayCounter < 10:
        retStr += "0"
    retStr += str(dayCounter)
    return retStr

def convertFileTime(timeCounter):
    hour = int(timeCounter / 60)
    minute = timeCounter % 60
    retStr = ""
    if hour < 10:
        retStr += "0"
    retStr += str(hour)
    if minute < 10:
        retStr += "0"
    retStr += str(minute)
    return retStr
    

def mergeCounts(tempCounts, fullRates):
    for tKey in tempCounts:
        if not tKey in fullRates:
            fullRates[tKey] = []
        fullRates[tKey].append(tempCounts[tKey])
    return fullRates

def flagPoss(myProc):
    inRegion = False
    curPeer = None
    curCounts = {}
    for lineB in iter(myProc.stdout.readline, b''):
        line = lineB.decode()
        if inRegion:
            prefixSearch = re.search(prefixRegex, line)
            if prefixSearch:
                curCounts[curPeer] = curCounts[curPeer] + 1
                continue
            else:
                inRegion = False
        fromSearch = re.search(peerRegex, line)
        if fromSearch:
            curPeer = fromSearch.group(1)
            if not curPeer in curCounts:
                curCounts[curPeer] = 0
            continue
        startUpdateSearch = re.search(updateStartRegex, line)
        if startUpdateSearch:
            inRegion = True
            continue
    winners = set([])
    for tKey in curCounts:
        if curCounts[tKey] > 100000:
            winners.add(tKey)
    return winners

def processFile(myProc, rates, winners):
    curCounts = {}

    curTime = 0
    curPeer = None
    inRegion = False 
    for lineB in iter(myProc.stdout.readline,b''):
        line = lineB.decode()
        if inRegion:
            prefixSearch = re.search(prefixRegex, line)
            if prefixSearch:
                curCounts[curPeer] = curCounts[curPeer] + 1
                continue
            else:
                inRegion = False
        timeSearch = re.search(timeRegex, line)
        if timeSearch:
            newTS = convertTS(timeSearch.group(1))
            if not newTS == curTime:
                rates = mergeCounts(curCounts, rates)
                curCounts = {}
                curTime = newTS
            continue
        fromSearch = re.search(peerRegex, line)
        if fromSearch:
            curPeer = fromSearch.group(1)
            
            if not curPeer in curCounts:
                curCounts[curPeer] = 0
            continue
        startUpdateSearch = re.search(updateStartRegex, line)
        if startUpdateSearch and (curPeer in winners):
            inRegion = True
            continue
    return rates

if __name__ == "__main__":
    lastTS = 0.0
    largeCount = 0
    wtfCount = 0
    termCount = 0

    urlBase = "ftp://archive.routeviews.org/bgpdata/2011.08/UPDATES/"
    fileBase = "updates.201108"
    fileTail = ".bz2"

    start = time.time()
    updateRates = {}
    posInjectFile = open("possFullDumps.txt", "w")
    rates = {}
    for day in range(14, 21):
        for i in range(1, 96):
            fileName = fileBase + convertDate(day) + "." + convertFileTime(i * 15) + fileTail
            fileUrl = urlBase + fileName
            print("fetching " + fileUrl)
            wgetProc = subprocess.Popen(["wget", fileUrl])
            wgetProc.wait()
            unzipProc = subprocess.Popen(["bunzip2", fileName])
            unzipProc.wait()
            fileName = fileBase + convertDate(day) + "." + convertFileTime(i * 15)
            bgpdumpProc = subprocess.Popen(["bgpdump", fileName], stdout=subprocess.PIPE)
            winners = flagPoss(bgpdumpProc)
            print("found " + str(len(winners)) + " winners")

            if len(winners) > 0:
                bgpdumpProc = subprocess.Popen(["bgpdump", fileName], stdout=subprocess.PIPE)
                print ("starting process of "  + fileName)
                rates = processFile(bgpdumpProc, rates, winners)
            else:
                print("skipping file since no winners found")
            os.remove(fileName)

    print("found rates for " + str(len(rates)))
    pickle.dump(rates, open("data/aug2011.dump", "wb"))

        
