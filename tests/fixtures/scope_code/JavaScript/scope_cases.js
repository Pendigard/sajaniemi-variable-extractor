let js_module_value = 1;

function jsNamed(value) {
  let sharedName = value;
  return sharedName;
}

const holder = {
  assigned: function assignedFunction(value) {
    let propertyLocal = value;
    return propertyLocal;
  }
};

const anonymousOwner = function (value) {
  let anonymousLocal = value;
  return anonymousLocal;
};

const blockArrow = (value) => {
  let blockLocal = value;
  return blockLocal;
};

const expressionArrow = (expressionValue) => expressionValue + 1;
function jsFirst(value) { let firstLocal = value; return firstLocal; } function jsSecond(value) { let secondLocal = value; return secondLocal; }
const minifiedArrow=(value)=>{let minifiedLocal=value;return minifiedLocal;};
const propertyHolder={first:function(){let firstPropertyLocal=1;return firstPropertyLocal;},second:function(){let secondPropertyLocal=2;return secondPropertyLocal;},"quoted-name":function(){let quotedPropertyLocal=3;return quotedPropertyLocal;},async action(value){let asyncPropertyLocal=value;return asyncPropertyLocal;}};

const readableHandlers = {
  first:function(){let readableFirstLocal = 1},
  second:function(){let readableSecondLocal = 2}
};
const minifiedHandlers={first:function(){let minifiedFirstPropertyLocal=1},second:function(){let minifiedSecondPropertyLocal=2}};
const lifecycleHandlers={onLoad:function(){let loadLocal="comma,in,string"},onShow:function(){let showLocal=[1,2].length}};
