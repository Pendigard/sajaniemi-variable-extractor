const globalFixed = 7;
let globalUninitialized;
let globalMutated = 1;
const globalItems = [];
function mutateGlobals() { globalMutated = 2; globalItems.push(2); }
function consume(value) { return value; }
function locals(source, readParameter, reassignedParameter, itemsParameter) {
  const constLocal = 7;
  let initializedLocal = 8;
  let assignedLaterLocal; assignedLaterLocal = source;
  let reassignedLocal = source; reassignedLocal = 2;
  let updatedLocal = source; updatedLocal++;
  let selfUpdate = source; selfUpdate = source + selfUpdate;
  consume(readParameter); reassignedParameter = source;
  const items = []; items.push(source); items[0] = source;
  itemsParameter.push(source);
  consume(constLocal); consume(initializedLocal); consume(assignedLaterLocal);
}
function loops(values) {
  const beforeLoop = values;
  for (const value of values) { consume(beforeLoop); let insideLoop = value; consume(insideLoop); }
  let changedInLoop = values;
  for (const value of values) { changedInLoop = value; }
}
