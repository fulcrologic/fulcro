<section class="md-content">
<h2>Available Blog Articles</h2>
</section>

<div class="max-width-12">
  {% for post in site.posts %}
    <div class="clearfix">
      <div class="col col-4"> {{post.date | date_to_long_string}} </div>
      <div class="col col-8"> <a href="/fulcro{{ post.url }}">{{ post.title }}</a> </div>
    </div>
    <div class="clearfix">
      <div class="col col-4">&nbsp;</div>
      <div class="col col-8"> by {{post.author}}</div>
    </div>
  {% endfor %}
</div>
