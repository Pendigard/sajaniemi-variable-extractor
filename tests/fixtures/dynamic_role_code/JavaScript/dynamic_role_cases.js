function readValue() { return 1; }

class Box {
  constructor(value) {
    this.value = value;
  }
}

class Profile {
  constructor(interest) {
    this.interest = interest;
  }
  setInterest(newInterest) {
    this.interest = newInterest;
  }
  getInterest() {
    return this.interest;
  }
}

function dynamicRoles(collection, fixedStep, unpredictable) {
  let stepper = 0;
  while (collection.length) {
    stepper += fixedStep;
    break;
  }
  let dynamicUpdate = 0;
  dynamicUpdate += collection[0];
  for (const element of collection) console.log(element);
  let current = null;
  while (collection.length) {
    current = readValue();
    console.log(current);
    break;
  }
  return stepper + dynamicUpdate;
}

function headerAcquisition(regex, text) {
  let param = null;
  while ((param = regex.exec(text))) {
    console.log(param[1]);
  }
  return param;
}
