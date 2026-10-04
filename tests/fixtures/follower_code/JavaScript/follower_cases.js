function observe(value) {
  return value;
}

function directBefore(values) {
  let currentBefore = 0;
  let previousBefore = null;
  for (const value of values) {
    previousBefore = currentBefore;
    currentBefore = value;
    observe(previousBefore);
  }
  return previousBefore;
}

function directAfter(values) {
  let currentAfter = 0;
  let previousAfter = -1;
  for (const value of values) {
    currentAfter = value;
    observe(previousAfter);
    previousAfter = currentAfter;
  }
  return previousAfter;
}

class Tracker {
  constructor() {
    this.previousMember = null;
    this.currentMember = 0;
    this.resetMember = null;
  }

  update(value) {
    this.previousMember = this.currentMember;
    this.currentMember = value;
    this.resetMember = this.currentMember;
  }

  reset() {
    this.resetMember = 0;
  }

  getPrevious() {
    return this.previousMember;
  }
}

function noEvolution(values) {
  const staticMaster = 1;
  let staticFollower = null;
  for (const value of values) {
    staticFollower = staticMaster;
  }
  return staticFollower;
}
