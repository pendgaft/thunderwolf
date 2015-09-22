#!/usr/bin/env python3

import pickle
import matplotlib.pyplot as plt

def filter(vals, minVal):
    outList = []
    for tVal in vals:
        if tVal >= minVal:
            outList.append(tVal)
    return outList


if __name__ == "__main__":
    fileName = "rates.csv"

    counts = []

    myMap = pickle.load(open(fileName, "rb"))

    for tKey in myMap:
        for tDatum in myMap[tKey]:
                counts.append(tDatum)
    print("starting plots")

    fig = plt.figure()
    plt.hist(counts, bins = 200, range=(0, 2000), normed = True)
    plt.xlabel("Updates Per Second")
    plt.ylabel("Normalized Freqeuncy")
    fig.savefig("distrib.pdf", format="pdf")

    fig = plt.figure()
    plt.hist(counts, bins = 200, range = (1, 2000), normed = True)
    fig.savefig("distrib-1.pdf", format = "pdf")

    fig = plt.figure()
    plt.hist(counts, bins = 200, range=(10, 2000), normed = True)
    fig.savefig("distrib-10.pdf", format = "pdf")

    fig = plt.figure()
    plt.hist(counts, bins = 200, range=(50, 2000), normed = True)
    fig.savefig("distrib-50.pdf", format = "pdf")




