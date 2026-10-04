function consume(value) { return value; }
function transform(value) { return value; }

let programTemp = transform(1);
consume(programTemp);

function positiveSingle(source) {
  let tempSingle = transform(source);
  consume(tempSingle);
}

function updateCases(source) {
  let plusEqual = transform(source);
  consume(plusEqual);
  plusEqual += source;
  consume(plusEqual);
  let postIncrement = transform(source);
  consume(postIncrement);
  postIncrement++;
  consume(postIncrement);
  let preIncrement = transform(source);
  consume(preIncrement);
  ++preIncrement;
  consume(preIncrement);
}

function loopCases(items) {
  for (const item of items) {
    let loopFresh = transform(item);
    consume(loopFresh);
  }
  let loopOld = transform(items);
  for (const item of items) {
    consume(loopOld);
    loopOld = transform(item);
  }
}
