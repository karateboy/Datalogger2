(window["webpackJsonp"]=window["webpackJsonp"]||[]).push([["login"],{"13d5":function(e,t,r){"use strict";var n=r("23e7"),a=r("d58f").left,o=r("a640"),s=r("ae40"),i=o("reduce"),u=s("reduce",{1:0});n({target:"Array",proto:!0,forced:!i||!u},{reduce:function(e){return a(this,e,arguments.length,arguments.length>1?arguments[1]:void 0)}})},2017:function(e,t,r){"use strict";r("dddc")},"9ed6":function(e,t,r){"use strict";r.r(t);var n=function(){var e=this,t=e.$createElement,r=e._self._c||t;return r("div",{staticClass:"login-container"},[r("el-form",{ref:"loginForm",staticClass:"login-form",attrs:{model:e.loginForm,rules:e.loginRules,autocomplete:"on","label-position":"left"}},[r("div",{staticClass:"title-container"},[r("h3",{staticClass:"title"},[e._v(" Login Form ")])]),r("el-form-item",{attrs:{prop:"username"}},[r("span",{staticClass:"svg-container"},[r("svg-icon",{attrs:{name:"user"}})],1),r("el-input",{ref:"username",attrs:{name:"username",type:"text",autocomplete:"on",placeholder:"username"},model:{value:e.loginForm.username,callback:function(t){e.$set(e.loginForm,"username",t)},expression:"loginForm.username"}})],1),r("el-form-item",{attrs:{prop:"password"}},[r("span",{staticClass:"svg-container"},[r("svg-icon",{attrs:{name:"password"}})],1),r("el-input",{key:e.passwordType,ref:"password",attrs:{type:e.passwordType,placeholder:"password",name:"password",autocomplete:"on"},nativeOn:{keyup:function(t){return!t.type.indexOf("key")&&e._k(t.keyCode,"enter",13,t.key,"Enter")?null:e.handleLogin(t)}},model:{value:e.loginForm.password,callback:function(t){e.$set(e.loginForm,"password",t)},expression:"loginForm.password"}}),r("span",{staticClass:"show-pwd",on:{click:e.showPwd}},[r("svg-icon",{attrs:{name:"password"===e.passwordType?"eye-off":"eye-on"}})],1)],1),r("el-button",{staticStyle:{width:"100%","margin-bottom":"30px"},attrs:{loading:e.loading,type:"primary"},nativeOn:{click:function(t){return t.preventDefault(),e.handleLogin(t)}}},[e._v(" Sign in ")]),r("div",{staticStyle:{position:"relative"}},[r("div",{staticClass:"tips"},[r("span",[e._v(" username: admin ")]),r("span",[e._v(" password: any ")])])])],1)],1)},a=[],o=(r("13d5"),r("b64b"),r("96cf"),r("1da1")),s=r("d4ec"),i=r("bee2"),u=r("262e"),c=r("2caf"),l=r("9ab4"),d=r("60a3"),p=r("9dba"),f=r("75fb"),m=function(e){Object(u["a"])(r,e);var t=Object(c["a"])(r);function r(){var e;return Object(s["a"])(this,r),e=t.apply(this,arguments),e.validateUsername=function(e,t,r){Object(f["b"])(t)?r():r(new Error("Please enter the correct user name"))},e.validatePassword=function(e,t,r){t.length<6?r(new Error("The password can not be less than 6 digits")):r()},e.loginForm={username:"admin",password:"111111"},e.loginRules={username:[{validator:e.validateUsername,trigger:"blur"}],password:[{validator:e.validatePassword,trigger:"blur"}]},e.passwordType="password",e.loading=!1,e.showDialog=!1,e.otherQuery={},e}return Object(i["a"])(r,[{key:"onRouteChange",value:function(e){var t=e.query;t&&(this.redirect=t.redirect,this.otherQuery=this.getOtherQuery(t))}},{key:"mounted",value:function(){""===this.loginForm.username?this.$refs.username.focus():""===this.loginForm.password&&this.$refs.password.focus()}},{key:"showPwd",value:function(){var e=this;"password"===this.passwordType?this.passwordType="":this.passwordType="password",this.$nextTick((function(){e.$refs.password.focus()}))}},{key:"handleLogin",value:function(){var e=this;this.$refs.loginForm.validate(function(){var t=Object(o["a"])(regeneratorRuntime.mark((function t(r){return regeneratorRuntime.wrap((function(t){while(1)switch(t.prev=t.next){case 0:if(!r){t.next=8;break}return e.loading=!0,t.next=4,p["a"].Login(e.loginForm);case 4:e.$router.push({path:e.redirect||"/",query:e.otherQuery}),setTimeout((function(){e.loading=!1}),500),t.next=9;break;case 8:return t.abrupt("return",!1);case 9:case"end":return t.stop()}}),t)})));return function(e){return t.apply(this,arguments)}}())}},{key:"getOtherQuery",value:function(e){return Object.keys(e).reduce((function(t,r){return"redirect"!==r&&(t[r]=e[r]),t}),{})}}]),r}(d["c"]);Object(l["a"])([Object(d["d"])("$route",{immediate:!0})],m.prototype,"onRouteChange",null),m=Object(l["a"])([Object(d["a"])({name:"Login"})],m);var g=m,h=g,w=(r("2017"),r("af14"),r("0c7c")),v=Object(w["a"])(h,n,a,!1,null,"05ecb0e6",null);t["default"]=v.exports},af14:function(e,t,r){"use strict";r("b1b8")},b1b8:function(e,t,r){},d58f:function(e,t,r){var n=r("1c0b"),a=r("7b0b"),o=r("44ad"),s=r("50c4"),i=function(e){return function(t,r,i,u){n(r);var c=a(t),l=o(c),d=s(c.length),p=e?d-1:0,f=e?-1:1;if(i<2)while(1){if(p in l){u=l[p],p+=f;break}if(p+=f,e?p<0:d<=p)throw TypeError("Reduce of empty array with no initial value")}for(;e?p>=0:d>p;p+=f)p in l&&(u=r(u,l[p],p,c));return u}};e.exports={left:i(!1),right:i(!0)}},dddc:function(e,t,r){e.exports={menuBg:"#304156",menuText:"#bfcbd9",menuActiveText:"#409eff"}}}]);
//# sourceMappingURL=login.fa5cd0ab.js.map