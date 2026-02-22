import { createClient } from 'https://esm.sh/@supabase/supabase-js@2';

Deno.serve(async (req) => {
  if (req.method !== 'POST') return new Response('MTU', { status: 405 });

  try {
    const authHeader = req.headers.get('Authorization');
    const supabase = createClient(
      Deno.env.get('SUPABASE_URL') ?? '',
      Deno.env.get('SUPABASE_ANON_KEY') ?? '',
      { global: { headers: { Authorization: authHeader! } } }
    );

    const buffer = await req.arrayBuffer();
    const bytes = new Uint8Array(buffer);
    if (bytes.length < 2) return new Response('Empty', { status: 400 });

    const uid = bytes[0];
    const payload = bytes.slice(1);

    // LANE ROUTING LOGIC
    // We map UIDs to specific lanes to prevent collision during 'Sips'
    const lanes = ['path_a', 'path_b', 'path_c', 'path_d', 'path_e', 'path_f'];
    const targetLane = lanes[uid % lanes.length];

    // Insert as a sequence of bytes for the receiver to reconstruct
    const inserts = Array.from(payload).map(byte => ({ b: byte }));

    const { error } = await supabase
      .from(targetLane)
      .insert(inserts);

    if (error) throw error;

    return new Response('ACK', { status: 200 });
  } catch (err) {
    console.error(err);
    return new Response(err.message, { status: 500 });
  }
});