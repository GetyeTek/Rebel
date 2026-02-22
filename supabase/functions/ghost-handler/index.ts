import { createClient } from 'https://esm.sh/@supabase/supabase-js@2';

Deno.serve(async (req) => {
  if (req.method !== 'POST') return new Response('Method Not Allowed', { status: 405 });

  try {
    const authHeader = req.headers.get('Authorization');
    const supabase = createClient(
      Deno.env.get('SUPABASE_URL') ?? '',
      Deno.env.get('SUPABASE_ANON_KEY') ?? '',
      { global: { headers: { Authorization: authHeader! } } }
    );

    const buffer = await req.arrayBuffer();
    const bytes = new Uint8Array(buffer);

    if (bytes.length < 1) return new Response('Empty Payload', { status: 400 });

    const uid = bytes[0];
    const payload = bytes.slice(1);

    const { error } = await supabase
      .from('ghost_stream')
      .insert({
        payload: payload,
        sender_id: uid
      });

    if (error) throw error;

    return new Response('OK', { status: 200 });
  } catch (err) {
    return new Response(err.message, { status: 500 });
  }
});