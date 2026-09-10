/* maplegend — lifted out of an inline <script> in map.html so the page carries
   no inline script, which is what lets script-src drop 'unsafe-inline'. */
(function(){
        function mirror(){
          var src=document.getElementById('netLegend'),dst=document.getElementById('mapLegend');if(!dst)return;
          var body=dst.querySelector('.ml-body');
          var hasContent=src&&src.textContent&&src.textContent.trim().length>0;
          var sel=document.getElementById('netColorBy');
          var th=dst.querySelector('.ml-h-label');
          if(th)th.textContent=(sel&&sel.options&&sel.options[sel.selectedIndex])?sel.options[sel.selectedIndex].text:'Road class';
          if(body)body.innerHTML=hasContent?src.innerHTML:'';
          var ro=document.getElementById('showRoads');
          var roadsOn=ro&&ro.checked;
          dst.classList.toggle('show',!!(hasContent&&roadsOn));
        }
        function enableDrag(){
          var el=document.getElementById('mapLegend'),handle=el&&el.querySelector('.ml-h'),container=document.getElementById('main');
          if(!el||!handle||!container)return;
          var dragging=false,offX=0,offY=0;
          function clamp(left,top){
            var maxL=container.clientWidth-el.offsetWidth,maxT=container.clientHeight-el.offsetHeight;
            return {left:Math.max(0,Math.min(left,Math.max(0,maxL))),top:Math.max(0,Math.min(top,Math.max(0,maxT)))};
          }
          try{
            var pos=JSON.parse(localStorage.getItem('klMapLegendPos')||'null');
            if(pos&&isFinite(pos.left)&&isFinite(pos.top)){el.style.left=pos.left+'px';el.style.top=pos.top+'px';el.style.bottom='auto';}
          }catch(e){}
          handle.addEventListener('pointerdown',function(e){
            if(e.target.closest('.ml-min'))return;
            var cRect=container.getBoundingClientRect(),eRect=el.getBoundingClientRect();
            offX=e.clientX-eRect.left;offY=e.clientY-eRect.top;
            dragging=true;
            el.style.left=(eRect.left-cRect.left)+'px';el.style.top=(eRect.top-cRect.top)+'px';el.style.bottom='auto';
            el.classList.add('dragging');
            try{handle.setPointerCapture(e.pointerId);}catch(err){}
            e.preventDefault();
          });
          handle.addEventListener('pointermove',function(e){
            if(!dragging)return;
            var cRect=container.getBoundingClientRect();
            var c=clamp(e.clientX-cRect.left-offX,e.clientY-cRect.top-offY);
            el.style.left=c.left+'px';el.style.top=c.top+'px';
          });
          function endDrag(e){
            if(!dragging)return;
            dragging=false;
            el.classList.remove('dragging');
            try{handle.releasePointerCapture(e.pointerId);}catch(err){}
            try{localStorage.setItem('klMapLegendPos',JSON.stringify({left:parseFloat(el.style.left),top:parseFloat(el.style.top)}));}catch(err){}
          }
          handle.addEventListener('pointerup',endDrag);
          handle.addEventListener('pointercancel',endDrag);
        }
        function enableMinimize(){
          var el=document.getElementById('mapLegend'),btn=el&&el.querySelector('.ml-min');
          if(!el||!btn)return;
          function apply(min){
            el.classList.toggle('min',min);
            btn.innerHTML=min?'&plus;':'&minus;';
            btn.title=min?'Expand':'Minimize';
          }
          var min=false;
          try{min=localStorage.getItem('klMapLegendMin')==='1';}catch(e){}
          apply(min);
          btn.addEventListener('click',function(e){
            e.stopPropagation();
            min=!min;
            apply(min);
            try{localStorage.setItem('klMapLegendMin',min?'1':'0');}catch(err){}
          });
        }
        function start(){
          var src=document.getElementById('netLegend');
          if(src&&window.MutationObserver){try{new MutationObserver(mirror).observe(src,{childList:true,subtree:true,characterData:true});}catch(e){}}
          ['netColorBy','showRoads'].forEach(function(id){var el=document.getElementById(id);if(el)el.addEventListener('change',function(){setTimeout(mirror,60);});});
          document.querySelectorAll('#iconrail .railbtn').forEach(function(b){b.addEventListener('click',function(){setTimeout(mirror,80);});});
          enableDrag();
          enableMinimize();
          setTimeout(mirror,800);setTimeout(mirror,2200);
        }
        if(document.readyState!=='loading')start();else document.addEventListener('DOMContentLoaded',start);
      })();
